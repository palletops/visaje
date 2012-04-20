(defproject visaje/visaje "0.1.0-SNAPSHOT" 
  :min-lein-version "2.0.0"
  :profiles {:dev
             {:dependencies
              [[ch.qos.logback/logback-core "1.0.1"]
               [ch.qos.logback/logback-classic "1.0.1"]]}}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [vmfest "0.2.5-SNAPSHOT"]
                 [ring "1.1.0-RC1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [clj-ssh "0.3.1"]]
  :description "Build VirtualBox images for fun and profit")