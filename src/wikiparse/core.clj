(ns wikiparse.core
  (:require [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.core.incubator :as incu]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [clojure.core.reducers :as r]
            [clojure.pprint :as pp]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.document :as es-doc]
            [clojurewerkz.elastisch.rest.index :as es-index]
            [clojurewerkz.elastisch.rest.bulk :as es-bulk]
            [clj-time [core :as t] [format :as tf] [coerce :as tc]]
            [wikiparse.db :as db])
  (:import (org.apache.commons.compress.compressors.bzip2 BZip2CompressorInputStream)
           (java.util.concurrent.atomic AtomicLong))
  (:gen-class))

(def script-id "wikiupdate")

(def consistency "one")

(def connection (atom nil))

(def stream-processed (AtomicLong. 0))

(def upsert-script "if (is_redirect) {ctx._source.redirects += redirect};
                             ctx._source.suggest.input += title;
                           if (!is_redirect) {
                             ctx._source.suggest.input += title;
                             ctx._source.title = title;
                             ctx._source.timestamp = timestamp;
                             ctx._source.format = format;
                             ctx._source.body = body;
                           };")

(def phase-stats (atom {}))

(defn connect!
  "Connect once to the ES cluster"
  [es]
  (swap! connection
         (fn [con]
           (if-not con
             (esr/connect (or es "http://localhost:9200"))
             con))))

(defmacro with-connection
  [es bind & body]
  `(let [~bind (connect! ~es)]
     ~@body))

(defn flattr
  [separator pre themap]
  (map
   (fn [[k v]]
     (let [prefix (if (and (not (empty? pre)) pre)
                    (str pre separator (name k))
                    (name k))]
       (if (map? v)
         (flattr separator prefix v)
         {(keyword prefix) v})))
   themap))

;; Adapted from
;; https://stackoverflow.com/questions/17901933/flattening-a-map-by-join-the-keys
(defn flatten-map
  ([nested-map separator]
   (flatten-map nested-map separator nil))
  ([nested-map separator pre]
   #_(apply merge
          (r/foldcat
           (r/flatten
            (r/mapcat
             (partial flattr separator pre)
             nested-map))))
   (map
    #(apply merge (flatten (flattr separator pre %)))
    nested-map)))

(defn bz2-reader
  "Returns a streaming Reader for the given compressed BZip2
  file. Use within (with-open)."
  [filename]
  (-> filename io/file io/input-stream BZip2CompressorInputStream. io/reader))

;; XML Mapping functions + helpers

(defn elem->map
  "Turns a list of elements into a map keyed by tag name. This doesn't
  work so well if tag names are repeated"
  ; TODO: tag names should probably be put into a collection (list?)
  [mappers]
  (fn [elems]
    (reduce (fn [m elem]
              (if-let [mapper ((:tag elem) mappers)]
                (assoc m (:tag elem) (mapper elem))
                m))
            {}
            elems)))

(def text-mapper (comp first :content))

(def int-mapper #(Integer/parseInt (text-mapper %)))

(defn attr-mapper
  [attr]
  (fn [{attrs :attrs}]
    (get attrs attr)))

(defn to-timestamp
  [timestr]
  (let [formatter (tf/formatters :date-time-no-ms)]
    (tc/to-timestamp (tf/parse formatter timestr))))

(def revision-mapper
  (comp 
   (elem->map 
    {:text text-mapper
     :timestamp (comp to-timestamp text-mapper)
     :format text-mapper})
   :content))

(def page-mappers
  {:title    text-mapper
   :ns       int-mapper
   :id       int-mapper
   :redirect (attr-mapper :title)
   :revision revision-mapper})

;; Parse logic

(defn match-tag
  "match an element by tag name"
  [tag-name]
  #(= tag-name (:tag %)))

(defn filter-page-elems
  [wikimedia-elems]
  (r/filter (match-tag :page) wikimedia-elems))

(defn xml->pages
  [parsed]
  (r/map (comp (elem->map page-mappers) :content)
        (filter-page-elems parsed)))

;; Elasticsearch indexing

(defn es-page-formatter-for
  "returns an fn that formats the page as a bulk action tuple for a given index"
  [index-name]
  (fn 
    [{title :title redirect :redirect {text :text timestamp :timestamp format :format} :revision :as page}]
    ;; the target-title ensures that redirects are filed under the article they are redirects for
    (let [target-title (or redirect title)]
      [{:update {:_id (string/lower-case target-title) :_index index-name :_type :page}}
       {
        :scripted_upsert true
        :script upsert-script
        :params {:redirect     title, :title title, :timestamp timestamp, :format format,
                 :target_title target-title, :body text
                 :is_redirect  (boolean redirect)}
        :upsert (merge {:title     target-title
                        :redirects (if redirect [title] [])
                        :suggest   {:input [title] :output target-title}
                        } (when (not redirect) {:body text :timestamp timestamp :format format}))
        }])))

(defn es-format-pages
  [index-name pages]
  (r/map (es-page-formatter-for index-name) pages))

(defn strip-text
  [max page]
  (if (> (count (get-in page [:revision :text])) max)
    (incu/dissoc-in page [:revision :text])
    page))

(defn phase-filter
  [phase]
  (cond (= :simultaneous phase) identity
        (= :redirects phase) :redirect
        (= :full phase) (comp nil? :redirect)
        :else nil))

(defn filter-pages
  [pages phase]
  (r/filter (phase-filter phase) (r/filter #(= 0 (:ns %)) pages)))

(defn bulk-index-pages
  [conn pages]
  ;; unnest command / doc tuples with apply concat
  ;(pp/pprint pages)
  (let [resp (es-bulk/bulk conn pages :consistency consistency)]
    (println "RANBULK" pages)
    (println resp)
    (when (:errors resp)
      (println resp))))

(defn index-pages
  [conn callback bulk-lines]
  (bulk-index-pages conn bulk-lines)
  (callback bulk-lines))

(def page-mapping
  {
   :_all {:_enabled false}
   :properties
    {
     :ns {:type :string :index :not_analyzed}
     :redirect {:type :string :index :not_analyzed}
     :title {
             :type :string
             :fields
             {
              :title_snow {:type :string :analyzer :snowball}
              :title_simple {:type :string :analyzer :simple}
              :title_exact {:type :string :index :not_analyzed}}}
     :redirects {
             :type :string
             :fields
             {
              :redirects_snow {:type :string :analyzer :snowball}
              :redirects_simple {:type :string :analyzer :simple}
              :redirects_exact {:type :string :index :not_analyzed}}}
     :body {
             :type :string
             :fields
             {
              :body_snow {:type :string :analyzer :snowball}
              :body_simple {:type :string :analyzer :simple}}}
     :suggest {
               :type :completion
               :index_analyzer :simple
               :search_analyzer :simple}
     :timestamp {:type :date}
     :format {:type :string :index :not_analyzed}
     }
   })

;; Bootstrap + Run

(defn ensure-index
  [conn name]
  (when (not (es-index/exists? conn name))
    (println (format "Deleting index %s" name))
    (es-index/delete conn name)
    (println (format "Creating index %s" name))
    (es-index/create conn name
                     :settings {
                                :index {
                                        :number_of_shards 1,
                                        :number_of_replicas 0
                                        :refresh_interval "300s"
                                        :gateway {:local {:sync "120s"}}
                                        :translog {
                                                   :interval "120s"
                                                   :flush_threshold_size "960mb"
                                                   }
                                        }
                                        :store {
                                                :throttle {
                                                           :max_bytes_per_sec "200mb"
                                                           :type "none"
                                                           }
                                }
                     :mappings {
                                :page page-mapping
                                }
                                })))

(defn count-stream-elems
  [chunk]
  (.getAndAdd stream-processed (count chunk))
  chunk)

(defn dump-pages
  [rdr formatter dumper phase batch-size]
  (dorun (pmap (fn [elems]
                (-> elems
                    (count-stream-elems)
                    (xml->pages)
                    (filter-pages phase)
                    (r/flatten)
                    (r/foldcat)
                    formatter
                    dumper))
              (partition-all batch-size (:content (xml/parse rdr))))))

(defn parse-cmdline
  [args]
  (let [[opts args banner]
        (cli/cli args
           "Usage: wikiparse [switches] path_to_bz2_wiki_dump"
           ["-h" "--help" "display this help and exit"]
           ["--es" "elasticsearch connection string" :default "http://localhost:9200"]
           ["-p" "--phases" "Which phases to execute in which order" :default "simultaneous"]
           ["--index" "elasticsearch index name" :default "en-wikipedia"]
           ["--batch" "Batch size for compute operations. Bigger batch requires more heap" :default "256"]
        )
        ]
    (when (or (empty? args) (:help opts))
      (println "Listening for input on stdin (try bzip2 -dcf en-wiki-dump.bz2 | java -jar wikiparse.jar)"))
    [opts (first args)]))

(defn new-phase-stats
  [name]
  {:name name
   :start (System/currentTimeMillis)
   :processed (AtomicLong.)
   })

(defn print-phase-stats
  [{:keys [start processed name] :as stats}]
  (let [total (.get processed)
        now (System/currentTimeMillis)
        elapsed-secs (/ (- now start) 1000.0)
        rate  (/ total elapsed-secs)]
    (println (format "Stream@%d Phase '%s' @ %s in %2f secs. (%2f p/s)"
                     (.get stream-processed)
                     name
                     (.get processed)
                     elapsed-secs
                     rate))))

(defn make-stats-reporter
  [{:keys [start processed]  :as stats}]
  (fn [bulk-lines]
    (let [processed-items (/ (count bulk-lines) 2)]
      (.addAndGet processed processed-items)
      (print-phase-stats stats)
      )))

(defn create-update-script
  [conn index-name]
  (es-doc/create conn ".scripts" "groovy"
                 {:script upsert-script}
                 :id script-id
                 ))

(defn write-pages
  "Write pages out to file"
  [file callback pages]
  (with-open [file (io/writer file :append true)]
    (pp/pprint pages file)
    (callback pages)))

(defn parse
  [path phases batch-size formatter dumper]
  (doseq [phase phases]
    (let [stats (swap! phase-stats (fn [_] (new-phase-stats phase)))
          reporter (make-stats-reporter stats)
          dumper (partial dumper reporter)
          runner (fn [rdr]
                   (println "Starting phase:" phase)
                   (println "Batch size:" (str batch-size))
                   (dorun
                    (dump-pages rdr formatter dumper phase batch-size)
                    ))]
      (.set stream-processed 0)
      (if path
        (with-open [rdr (bz2-reader path)] (runner rdr))
        (runner *in*))
      (print-phase-stats stats)
      (println "Completed phase:" phase)
      )))

(defn get-phases
  [opts]
  (map keyword (string/split (:phases opts) #",")))

(defn -main
  [& args]
  (let [[opts path] (parse-cmdline args)]
    ; This should be handled with components instead (most likely)
    #_(with-connection (:es opts) conn
        (ensure-index conn (:index opts))
        (create-update-script conn (:index opts)))
    (parse path
           (get-phases opts)
           (Integer/parseInt (:batch opts))
           #(flatten-map % "-")
           (partial write-pages "parsed.edn")))
  (System/exit 0))
