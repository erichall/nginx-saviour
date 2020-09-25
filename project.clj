(defproject nginx-saviour "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.474"]
                 [clj-time "0.15.2"]
                 [com.taoensso/timbre "5.0.0"]
                 [clojure-watch "0.1.14"]]
  :main ^:skip-aot nginx-saviour.core
  :target-path "target/%s"
  :profiles {:dev     {:resource-paths ["config/dev"]
                       :injections     [(println "including dev profile")]
                       }
             :uberjar {
                       :resource-paths ["config/release"]
                       :aot            :all
                       :injections     [(println "including prod profile")]
                       }
             }
  )