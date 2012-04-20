(ns visaje.file-server
  (:use [ring.adapter.jetty :only [run-jetty]]))

(defonce seeds (atom {}))
(defonce last-seed-id (atom 0))
(defonce server (run-jetty #'file-server {:port 8055 :join? false}))

(defn file-server [{:keys [uri] :as req}]
  (let [[_ seed-id file-name]  (clojure.string/split uri #"/")
        content (get-in @seeds [seed-id file-name])]
    (println "seed-id " seed-id "file-name" file-name)
    (if content
      {:status 200
       :headers {"ContentType" "text/plain"}
       :body content}
      {:status 404})))

(defn register-seed-files [name-content-map]
  (let [seed-id (swap! last-seed-id inc)]
    (swap! seeds assoc (str seed-id) name-content-map)
    (when (and (> (count @seeds) 0)
               (not (.isStarted server)))
      (println "Starting server")
      (.start server))
    seed-id))

(defn unregister-seed-files [seed-id]
  (swap! seeds dissoc (str seed-id))
  (when (= 0 (count @seeds))
    (println "Shutting down server")
    (.stop server)))

