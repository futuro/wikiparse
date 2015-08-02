(ns wikiparse.db
  (:require [clojure.java.jdbc :as sql]
            [clojure.pprint :as pp]
            [honeysql.core :as honey]
            [honeysql.helpers :as helpers]))

(defn add-nil-redirects
  [pages]
  (map #(merge {:redirect nil} %) pages))

(defn insert-maps
  [db callback nestedmaps]
  (let [maps (add-nil-redirects nestedmaps)]
    (try
      (sql/execute! db
                    (-> (helpers/insert-into :pages)
                        (helpers/values maps)
                        honey/format))
      (catch Exception e
        (prn (.getNextException e)))))
  (callback nestedmaps))
