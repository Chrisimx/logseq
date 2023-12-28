(ns frontend.handler.file-based.property.util
  "Property fns needed by the rest of the app and not graph-parser"
  (:require [clojure.string :as string]
            [frontend.util :as util]
            [clojure.set :as set]
            [frontend.config :as config]
            [logseq.graph-parser.property :as gp-property :refer [properties-start properties-end]]
            [logseq.graph-parser.util.page-ref :as page-ref]
            [frontend.format.mldoc :as mldoc]
            [logseq.graph-parser.text :as text]
            [frontend.db :as db]
            [frontend.state :as state]
            [frontend.util.cursor :as cursor]
            [frontend.worker.file.property-util :as wpu]))

(defn hidden-properties
  "These are properties hidden from user including built-in ones and ones
  configured by user"
  []
  (set/union
   (gp-property/hidden-built-in-properties)
   (set (config/get-block-hidden-properties))))

;; TODO: Investigate if this behavior is correct for configured hidden
;; properties and for editable built in properties
(def built-in-properties
  "Alias to hidden-properties to keep existing behavior"
  hidden-properties)

(defn remove-empty-properties
  [content]
  (if (gp-property/contains-properties? content)
    (string/replace content
                    (re-pattern ":PROPERTIES:\n+:END:\n*")
                    "")
    content))

(def simplified-property? wpu/simplified-property?)

(defn- get-property-key
  [line format]
  (and (string? line)
       (when-let [key (last
                       (if (= format :org)
                         (util/safe-re-find #"^\s*:([^: ]+): " line)
                         (util/safe-re-find #"^\s*([^ ]+):: " line)))]
         (keyword key))))

(defn- org-property?
  [line]
  (boolean
   (and (string? line)
        (util/safe-re-find #"^\s*:[^: ]+: " line)
        (when-let [key (get-property-key line :org)]
          (not (contains? #{:PROPERTIES :END} key))))))

(defn- get-org-property-keys
  [content]
  (let [content-lines (string/split-lines content)
        [_ properties&body] (split-with #(-> (string/triml %)
                                             string/upper-case
                                             (string/starts-with? properties-start)
                                             not)
                                        content-lines)
        properties (rest (take-while #(-> (string/trim %)
                                          string/upper-case
                                          (string/starts-with? properties-end)
                                          not
                                          (or (string/blank? %)))
                                     properties&body))]
    (when (seq properties)
      (map #(->> (string/split % ":")
                 (remove string/blank?)
                 first
                 string/upper-case)
           properties))))

(defn- get-markdown-property-keys
  [content]
  (let [content-lines (string/split-lines content)
        properties (filter #(re-matches (re-pattern (str "^.+" gp-property/colons "\\s*.+")) %)
                           content-lines)]
    (when (seq properties)
      (map #(->> (string/split % gp-property/colons)
                 (remove string/blank?)
                 first
                 string/upper-case)
           properties))))

(defn- get-property-keys
  [format content]
  (cond
    (gp-property/contains-properties? content)
    (get-org-property-keys content)

    (= :markdown format)
    (get-markdown-property-keys content)))

(defn property-key-exist?
  [format content key]
  (let [key (string/upper-case key)]
    (contains? (set (util/remove-first #{key} (get-property-keys format content))) key)))

(defn goto-properties-end
  [_format input]
  (cursor/move-cursor-to-thing input properties-start 0)
  (let [from (cursor/pos input)]
    (cursor/move-cursor-to-thing input properties-end from)))

(defn remove-properties
  [format content]
  (cond
    (gp-property/contains-properties? content)
    (let [lines (string/split-lines content)
          [title-lines properties&body] (split-with #(-> (string/triml %)
                                                         string/upper-case
                                                         (string/starts-with? properties-start)
                                                         not)
                                                    lines)
          body (drop-while #(-> (string/trim %)
                                string/upper-case
                                (string/starts-with? properties-end)
                                not
                                (or (string/blank? %)))
                           properties&body)
          body (if (and (seq body)
                        (-> (first body)
                            string/triml
                            string/upper-case
                            (string/starts-with? properties-end)))
                 (let [line (string/replace (first body) #"(?i):END:\s?" "")]
                   (if (string/blank? line)
                     (rest body)
                     (cons line (rest body))))
                 body)]
      (->> (concat title-lines body)
           (string/join "\n")))

    (not= format :org)
    (let [lines (string/split-lines content)
          lines (if (simplified-property? (first lines))
                  (drop-while simplified-property? lines)
                  (cons (first lines)
                        (drop-while simplified-property? (rest lines))))]
      (string/join "\n" lines))

    :else
    content))

;; title properties body
(defn with-built-in-properties
  [properties content format]
  (let [org? (= format :org)
        properties (filter (fn [[k _v]] ((built-in-properties) k)) properties)]
    (if (seq properties)
      (let [lines (string/split-lines content)
            ast (mldoc/->edn content format)
            [title body] (if (mldoc/block-with-title? (first (ffirst ast)))
                           [(first lines) (rest lines)]
                           [nil lines])
            properties-in-content? (and title (= (string/upper-case title) properties-start))
            no-title? (or (simplified-property? title) properties-in-content?)
            properties&body (concat
                                 (when (and no-title? (not org?)) [title])
                                 (if (and org? properties-in-content?)
                                   (rest body)
                                   body))
            {properties-lines true body false} (group-by (fn [s]
                                                           (or (simplified-property? s)
                                                               (and org? (org-property? s)))) properties&body)
            body (if org?
                   (remove (fn [s] (contains? #{properties-start properties-end} (string/trim s))) body)
                   body)
            properties-in-content (->> (map #(get-property-key % format) properties-lines)
                                       (remove nil?)
                                       (set))
            properties (remove (comp properties-in-content first) properties)
            built-in-properties-area (map (fn [[k v]]
                                            (if org?
                                              (str ":" (name k) ": " v)
                                              (str (name k) gp-property/colons " " v))) properties)
            body (concat (if no-title? nil [title])
                         (when org? [properties-start])
                         built-in-properties-area
                         properties-lines
                         (when org?
                           [properties-end])
                         body)]
        (string/triml (string/join "\n" body)))
      content)))

;; FIXME:
(defn front-matter?
  [s]
  (string/starts-with? s "---\n"))

(defn insert-property
  "Only accept nake content (without any indentation)"
  ([format content key value]
   (insert-property format content key value false))
  ([format content key value front-matter?]
   (let [repo (state/get-current-repo)]
     (wpu/insert-property repo format content key value front-matter?))))

(defn insert-properties
  [format content kvs]
  (reduce
   (fn [content [k v]]
     (let [k (if (string? k)
               (keyword (-> (string/lower-case k)
                            (string/replace " " "-")))
               k)
           v (if (coll? v)
               (some->>
                (seq v)
                (distinct)
                (map (fn [item] (page-ref/->page-ref (text/page-ref-un-brackets! item))))
                (string/join ", "))
               v)]
       (insert-property format content k v)))
   content kvs))

(def remove-property wpu/remove-property)

(defn remove-id-property
  [format content]
  (remove-property format "id" content false))

;; FIXME: remove only from the properties area, not other blocks such as
;; code blocks, quotes, etc.
;; Currently, this function will do nothing if the content is a code block.
;; The future plan is to separate those properties from the block' content.
(defn remove-built-in-properties
  [format content]
  (when content
    (let [trim-content (string/trim content)]
      (if (or
           (and (= format :markdown)
                (string/starts-with? trim-content "```")
                (string/ends-with? trim-content "```"))
           (and (= format :org)
                (string/starts-with? trim-content "#+BEGIN_SRC")
                (string/ends-with? trim-content "#+END_SRC")))
        content
        (let [built-in-properties* (built-in-properties)
              content (reduce (fn [content key]
                                (remove-property format key content)) content built-in-properties*)]
          (if (= format :org)
            (string/replace-first content (re-pattern ":PROPERTIES:\n:END:\n*") "")
            content))))))

(def hidden-editable-page-properties
  "Properties that are hidden in the pre-block (page property)"
  #{:title :filters :icon})

(assert (set/subset? hidden-editable-page-properties (gp-property/editable-built-in-properties))
        "Hidden editable page properties must be valid editable properties")

(def hidden-editable-block-properties
  "Properties that are hidden in a block (block property)"
  (into #{:logseq.query/nlp-date}
        gp-property/editable-view-and-table-properties))

(assert (set/subset? hidden-editable-block-properties (gp-property/editable-built-in-properties))
        "Hidden editable page properties must be valid editable properties")

(defn- add-aliases-to-properties
  "Adds aliases to a page when a page has aliases and is also an alias of other pages"
  [properties page-id]
  (let [repo (state/get-current-repo)
        aliases (db/get-page-alias-names repo
                                         (:block/name (db/pull page-id)))]
    (if (seq aliases)
      (if (:alias properties)
        (update properties :alias (fn [c]
                                    (util/distinct-by string/lower-case (concat c aliases))))
        (assoc properties :alias aliases))
      properties)))

(defn get-visible-ordered-properties
  "Given a block's properties, order of properties and any display context,
  returns a tuple of property pairs that are visible when not being edited"
  [properties* properties-order {:keys [pre-block? page-id]}]
  (let [dissoc-keys (fn [m keys] (apply dissoc m keys))
        properties (cond-> (update-keys properties* keyword)
                     true
                     (dissoc-keys (hidden-properties))
                     pre-block?
                     (dissoc-keys hidden-editable-page-properties)
                     (not pre-block?)
                     (dissoc-keys hidden-editable-block-properties)
                     pre-block?
                     (add-aliases-to-properties page-id))]
    (if (seq properties-order)
      (keep (fn [k] (when (contains? properties k) [k (get properties k)]))
            (distinct properties-order))
      properties*)))
