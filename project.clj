(defproject wikiparse "1.2.6"
  :description "Import Wikipedia data into elasticsearch"
  :url "http://example.com/FIXME"
  :aot [wikiparse.core]
  :main wikiparse.core
  :jvm-opts ["-Xmx2g" "-server"]
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [org.clojure/tools.cli "0.2.4"]
                 [clojurewerkz/elastisch "2.2.0-beta3"]
                 [org.apache.commons/commons-compress "1.9"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [honeysql "0.6.1"]
                 [korma "0.4.2"]
                 [clj-time "0.10.0"]])

