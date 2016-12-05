(ns io.perun
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [boot.util :as u]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [io.perun.core :as perun]))

(def ^:private global-deps
  '[])

(defn- create-pod' [deps]
  (-> (boot/get-env)
      (update-in [:dependencies] into global-deps)
      (update-in [:dependencies] into deps)
      pod/make-pod))

(defn- create-pod
  [deps]
  (future (create-pod' deps)))

(defn- commit [fileset tmp]
  (-> fileset
      (boot/add-resource tmp)
      boot/commit!))

(def ^:private print-meta-deps
  '[[mvxcvi/puget "1.0.0"]])

(def print-meta-pod (delay (create-pod' print-meta-deps)))

(deftask print-meta
  "Utility task to print perun metadata"
  [m map-fn MAPFN code "function to map over metadata items before printing"]
  (boot/with-pre-wrap fileset
    (let [map-fn (or map-fn identity)]
      (pod/with-call-in @print-meta-pod
        (io.perun.print-meta/print-meta ~(vec (map map-fn (perun/get-meta fileset))))))
    fileset))

(defn trace
  [kw entries]
  (map #(update-in % [:io.perun/trace] (fnil conj []) kw) entries))

(def ^:private filedata-deps
  '[[com.novemberain/pantomime "2.8.0"]])

;; The namespace is stateless etc. so one is enough
(def filedata-pod (delay (create-pod' filedata-deps)))

(defn add-filedata [tmp-files]
  (pod/with-call-in @filedata-pod
    (io.perun.filedata/filedatas
     ~(vec (map (juxt boot/tmp-path #(.getPath (boot/tmp-file %)) perun/+meta-key+) tmp-files)))))

(deftask base
  "Add some basic information to the perun metadata and
   establish metadata structure."
  []
  (boot/with-pre-wrap fileset
    (let [updated-files (->> fileset
                             boot/user-files
                             add-filedata
                             (trace :io.perun/base))]
      (perun/set-meta fileset updated-files))))

(def ^:private images-dimensions-deps
  '[[image-resizer "0.1.8"]])

(deftask images-dimensions
  "Add images' dimensions to the file metadata:
   - width
   - height"
  []
  (boot/with-pre-wrap fileset
    (let [pod (create-pod images-dimensions-deps)
          files (->> fileset
                     boot/user-files
                     (boot/by-ext ["png" "jpeg" "jpg"])
                     add-filedata
                     (trace :io.perun/images-dimensions))
          updated-files (pod/with-call-in @pod
                         (io.perun.contrib.images-dimensions/images-dimensions ~files {}))]
      (perun/set-meta fileset updated-files))))

(def ^:private images-resize-deps
  '[[image-resizer "0.1.8"]])

(def ^:private +images-resize-defaults+
  {:out-dir "public"
   :resolutions #{3840 2560 1920 1280 1024 640}})

(deftask images-resize
  "Resize images to the provided resolutions.
   Each image file would have resolution appended to it's name:
   e.x. san-francisco.jpg would become san-francisco-3840.jpg"
  [o out-dir     OUTDIR       str    "the output directory"
   r resolutions RESOLUTIONS  #{int} "resoulitions to which images should be resized"]
  (boot/with-pre-wrap fileset
    (let [options (merge +images-resize-defaults+ *opts*)
          tmp (boot/tmp-dir!)
          pod (create-pod images-resize-deps)
          files (->> fileset
                     boot/user-files
                     (boot/by-ext ["png" "jpeg" "jpg"])
                     add-filedata
                     (trace :io.perun/images-resize))
          updated-files (pod/with-call-in @pod
                         (io.perun.contrib.images-resize/images-resize ~(.getPath tmp) ~files ~options))]
      (perun/report-debug "images-resize" "new resized images" updated-files)
      (perun/set-meta fileset updated-files)
      (commit fileset tmp))))

(defn content-pre-wrap
  [parse-form file-exts tracer options]
  (let [tmp     (boot/tmp-dir!)
        prev-fs (atom nil)]
    (boot/with-pre-wrap fileset
      (let [changed-files (->> (boot/fileset-diff @prev-fs fileset :hash)
                               boot/user-files
                               (boot/by-ext file-exts)
                               add-filedata)
            fs-files (->> fileset
                          boot/user-files
                          (boot/by-ext file-exts)
                          set)
            prev-fs-files (if @prev-fs
                            (->> @prev-fs
                                 boot/user-files
                                 (boot/by-ext file-exts)
                                 set)
                            #{})
            all-files (set/intersection fs-files prev-fs-files)
            unchanged-metadata (->> changed-files
                                    set
                                    (set/difference all-files)
                                    (keep perun/+meta-key+))
            input-metadata (->>
                            (if-let [pod (:pod options)]
                              (pod/with-call-in @pod ~(parse-form changed-files))
                              (eval (parse-form changed-files)))
                            (trace tracer))
            output-metadata (doall
                             (for [{:keys [path parsed filename] :as entry*} (concat unchanged-metadata
                                                                                     input-metadata)]
                               (let [page-filepath (string/replace path #"(?i).[a-z]+$" ".html")
                                     entry (-> entry*
                                               (assoc :has-content true)
                                               (assoc :path page-filepath)
                                               (assoc :filename (string/replace filename
                                                                                #"(?i).[a-z]+$" ".html"))
                                               (dissoc :parsed :extension :file-type :parent-path
                                                       :full-path :mime-type :original))]
                                 (perun/create-file tmp page-filepath parsed)
                                 entry)))
            final-metadata (perun/merge-meta* (perun/get-meta @prev-fs)
                                              (concat input-metadata output-metadata))
            new-fs (-> fileset
                       (commit tmp)
                       (perun/set-meta final-metadata))]
        (reset! prev-fs new-fs)
        new-fs))))

(def ^:private markdown-deps
  '[[org.pegdown/pegdown "1.6.0"]
    [circleci/clj-yaml "0.5.5"]])

(def ^:private +markdown-defaults+
  {:meta {:original true
          :include-rss true
          :include-atom true}})

(deftask markdown
  "Parse markdown files

   This task will look for files ending with `md` or `markdown`
   and add a `:parsed` key to their metadata containing the
   HTML resulting from processing markdown file's content"
  [m meta    META edn "metadata to set on each entry; keys here will be overridden by metadata in each file"
   o options OPTS edn "options to be passed to the markdown parser"]
  (let [pod     (create-pod markdown-deps)
        options (merge +markdown-defaults+ {:pod pod} *opts*)]
    (content-pre-wrap
     (fn [files] `(io.perun.markdown/parse-markdown ~files ~(dissoc options :pod)))
     ["md" "markdown"]
     :io.perun/markdown
     options)))

(deftask global-metadata
  "Read global metadata from `perun.base.edn` or configured file.

   The global metadata will be attached to fileset where it can be
   read and manipulated by the tasks. Render tasks will pass this
   as the first argument to render functions."
  [n filename NAME str "filename to read global metadata from"]
  (boot/with-pre-wrap fileset
    (let [meta-file (or filename "perun.base.edn")
          global-meta
            (some->> fileset
                     boot/user-files
                     (boot/by-name [meta-file])
                     first
                     boot/tmp-file
                     slurp
                     read-string)]
         (perun/report-info "global-metadata" "read global metadata from %s" meta-file)
         (perun/set-global-meta fileset global-meta))))

(def ^:private ttr-deps
  '[[time-to-read "0.1.0"]])

(deftask ttr
  "Calculate time to read for each file. Add `:ttr` key to the files' meta"
  []
  (let [pod (create-pod ttr-deps)]
    (boot/with-pre-wrap fileset
      (let [files         (perun/get-meta fileset)
            updated-files (trace :io.perun/ttr
                                 (pod/with-call-in @pod
                                   (io.perun.ttr/calculate-ttr ~files)))]
        (perun/report-debug "ttr" "generated time-to-read" (map :ttr updated-files))
        (perun/set-meta fileset updated-files)))))

(deftask word-count
  "Count words in each file. Add `:word-count` key to the files' meta"
  []
  (let [pod (create-pod ttr-deps)]
    (boot/with-pre-wrap fileset
      (let [files         (perun/get-meta fileset)
            updated-files (trace :io.perun/word-count
                                 (pod/with-call-in @pod
                                   (io.perun.word-count/count-words ~files)))]
        (perun/report-debug "word-count" "counted words" (map :word-count updated-files))
        (perun/set-meta fileset updated-files)))))


(def ^:private gravatar-deps
  '[[gravatar "0.1.0"]])

(deftask gravatar
  "Find gravatar urls using emails"
  [s source-key SOURCE-PROP kw "email property used to lookup gravatar url"
   t target-key TARGET-PROP kw "property name to store gravatar url"]
  (let [pod (create-pod gravatar-deps)]
    (boot/with-pre-wrap fileset
      (let [files         (perun/get-meta fileset)
            updated-files (trace :io.perun/gravatar
                                 (pod/with-call-in @pod
                                   (io.perun.gravatar/find-gravatar ~files ~source-key ~target-key)))]
        (perun/report-debug "gravatar" "found gravatars" (map target-key updated-files))
       (perun/set-meta fileset updated-files)))))

;; Should be handled by more generic filterer options to other tasks
(deftask draft
  "Exclude draft files"
  []
  (boot/with-pre-wrap fileset
    (let [files         (perun/get-meta fileset)
          updated-files (->> files
                             (remove #(true? (:draft %)))
                             (trace :io.perun/draft))]
      (perun/report-info "draft" "removed draft files. Remaining %s files" (count updated-files))
      (perun/set-meta fileset updated-files))))

(deftask build-date
  "Add :date-build attribute to each file metadata and also to the global meta"
  []
  (boot/with-pre-wrap fileset
    (let [files           (perun/get-meta fileset)
          global-meta     (perun/get-global-meta fileset)
          now             (java.util.Date.)
          updated-files   (->> files
                               (map #(assoc % :date-build now))
                               (trace :io.perun/build-date))
          new-global-meta (assoc global-meta :date-build now)
          updated-fs      (perun/set-meta fileset updated-files)]
        (perun/report-debug "build-date" "added :date-build" (map :date-build updated-files))
        (perun/report-info "build-date" "added date-build to %s files" (count updated-files))
      (perun/set-global-meta updated-fs new-global-meta))))

(def ^:private +slug-defaults+
  {; Parses `slug` portion out of the filename in the format: YYYY-MM-DD-slug-title.ext
   ; Jekyll uses the same format by default.
   :slug-fn (fn [filename] (->> (string/split filename #"[-\.]")
                                (drop 3)
                                drop-last
                                (string/join "-")
                                string/lower-case))
   :filterer :has-content})

(deftask slug
  "Adds :slug key to files metadata. Slug is derived from filename."
  [s slug-fn  SLUGFN code "function to build slug from filename"
   _ filterer FILTER code "predicate to use for selecting entries (default: `:has-content`)"]
  (boot/with-pre-wrap fileset
    (let [options       (merge +slug-defaults+ *opts*)
          slug-fn       (:slug-fn options)
          files         (filter (:filterer options) (perun/get-meta fileset))
          updated-files (->> files
                             (map #(assoc % :slug (-> % :filename slug-fn)))
                             (trace :io.perun/slug))]
      (perun/report-debug "slug" "generated slugs" (map :slug updated-files))
      (perun/report-info "slug" "added slugs to %s files" (count updated-files))
      (perun/set-meta fileset updated-files))))

(def ^:private +permalink-defaults+
  {:permalink-fn (fn [m] (perun/absolutize-url (str (:slug m) "/index.html")))
   :filterer     :has-content})

(deftask permalink
  "Adds :permalink key to files metadata. Value of key will determine target path.

   Make files permalinked. E.x. about.html will become about/index.html"
  [p permalink-fn PERMALINKFN code "function to build permalink from TmpFile metadata"
   _ filterer     FILTER      code "predicate to use for selecting entries (default: `:has-content`)"]
  (boot/with-pre-wrap fileset
    (let [options       (merge +permalink-defaults+ *opts*)
          files         (filter (:filterer options) (perun/get-meta fileset))
          assoc-perma   #(assoc % :permalink ((:permalink-fn options) %))
          updated-files (->> files
                             (map assoc-perma)
                             (trace :io.perun/permalink))]
      (perun/report-debug "permalink"  "generated permalinks" (map :permalink updated-files))
      (perun/report-info "permalink" "added permalinks to %s files" (count updated-files))
      (perun/merge-meta fileset updated-files))))

(def ^:private +canonical-url-defaults+
  {:filterer :has-content})

(deftask canonical-url
  "Adds :canonical-url key to files metadata.

   The url is concatenation of :base-url in global metadata and files' permaurl.
   The base-url must end with '/'."
  [_ filterer FILTER code "predicate to use for selecting entries (default: `:has-content`)"]
  (boot/with-pre-wrap fileset
    (let [options       (merge +canonical-url-defaults+ *opts*)
          files         (filter (:filterer options) (perun/get-meta fileset))
          base-url      (perun/assert-base-url (:base-url (perun/get-global-meta fileset)))
          assoc-can-url
            #(assoc %
                  :canonical-url
                  ; we need to call perun/relativize-url to remove leading / because base-url has trailing /
                  (str base-url (perun/relativize-url (:permalink  %))))
          updated-files (->> files
                             (map assoc-can-url)
                             (trace :io.perun/canonical-url))]
        (perun/report-info "canonical-url" "added canonical urls to %s files" (count updated-files))
        (perun/merge-meta fileset updated-files))))

(def ^:private sitemap-deps
  '[[sitemap "0.2.4"]
    [clj-time "0.12.0"]])

(def ^:private +sitemap-defaults+
  {:filename "sitemap.xml"
   :filterer :has-content
   :out-dir "public"})

(deftask sitemap
  "Generate sitemap"
  [f filename FILENAME str  "generated sitemap filename"
   _ filterer FILTER   code "predicate to use for selecting entries (default: `:has-content`)"
   o out-dir  OUTDIR   str  "the output directory"
   u url      URL      str  "base URL"]
  (let [pod     (create-pod sitemap-deps)
        tmp     (boot/tmp-dir!)
        options (merge +sitemap-defaults+ *opts*)]
    (boot/with-pre-wrap fileset
      (let [files (filter (:filterer options) (perun/get-meta fileset))]
        (pod/with-call-in @pod
          (io.perun.sitemap/generate-sitemap ~(.getPath tmp) ~files ~(dissoc options :filterer)))
        (commit fileset tmp)))))

(def ^:private rss-deps
  '[[clj-rss "0.2.3"]
    [clj-time "0.12.0"]])

(def ^:private +rss-defaults+
  {:filename "feed.rss"
   :filterer :include-rss
   :out-dir "public"})

(deftask rss
  "Generate RSS feed"
  [f filename    FILENAME    str  "generated RSS feed filename"
   _ filterer    FILTER      code "predicate to use for selecting entries (default: `:content`)"
   o out-dir     OUTDIR      str  "the output directory"
   t site-title  TITLE       str  "feed title"
   p description DESCRIPTION str  "feed description"
   l base-url    LINK        str  "feed link"]
  (let [pod (create-pod rss-deps)
        tmp (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (let [global-meta   (perun/get-global-meta fileset)
            options       (merge +rss-defaults+ global-meta *opts*)
            files         (filter (:filterer options) (perun/get-meta fileset))]
        (perun/assert-base-url (:base-url options))
        (pod/with-call-in @pod
          (io.perun.rss/generate-rss ~(.getPath tmp) ~files ~(dissoc options :filterer)))
        (commit fileset tmp)))))

(def ^:private atom-deps
  '[[org.clojure/data.xml "0.0.8"]
    [clj-time "0.12.0"]])

(def ^:private +atom-defaults+
  {:filename "atom.xml"
   :filterer :include-atom
   :out-dir "public"})

(deftask atom-feed
  "Generate Atom feed"
  [f filename    FILENAME    str  "generated Atom feed filename"
   _ filterer    FILTER      code "predicate to use for selecting entries (default: `:content`)"
   o out-dir     OUTDIR      str  "the output directory"
   t site-title  TITLE       str  "feed title"
   s subtitle    SUBTITLE    str  "feed subtitle"
   p description DESCRIPTION str  "feed description"
   l base-url    LINK        str  "feed link"]
  (let [pod (create-pod atom-deps)
        tmp (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (let [global-meta   (perun/get-global-meta fileset)
            options       (merge +atom-defaults+ global-meta *opts*)
            files         (filter (:filterer options) (perun/get-meta fileset))]
        (perun/assert-base-url (:base-url options))
        (pod/with-call-in @pod
          (io.perun.atom/generate-atom ~(.getPath tmp) ~files ~(dissoc options :filterer)))
        (commit fileset tmp)))))

(defn- assert-renderer [sym]
  (assert (and (symbol? sym) (namespace sym))
          "Renderer must be a fully qualified symbol, i.e. 'my.ns/fun"))

(defn- render-in-pod [pod sym render-data]
  (assert-renderer sym)
  (pod/with-eval-in pod
    (require '~(symbol (namespace sym)))
    ((resolve '~sym) ~(pod/send! render-data))))

(def ^:private +render-defaults+
  {:out-dir  "public"
   :filterer :has-content})

(def ^:private render-deps
  '[[org.clojure/tools.namespace "0.3.0-alpha3"]])

(def render-pod (delay (create-pod' render-deps)))

(defn render-to-paths
  "Renders paths in `data`, using `renderer` in `pod`, and writes
  the result to `tmp`.

  `data` should be a map with keys that are fileset paths, and
  values that are themselves maps with these keys:
   - `:render-data` the map argument that `renderer` will be called with
   - `:entry` the metadata for the item being rendered

  All `:entry`s will be returned, with their `:path`s set, `:has-content`
  set to `true`, and `tracer` added to `io.perun/trace`."
  [data renderer tmp tracer]
  (pod/with-call-in @render-pod
    (io.perun.render/update!))
  (doall
   (trace tracer
          (for [[path {:keys [render-data entry]}] data]
            (let [content (render-in-pod @render-pod renderer render-data)]
              (perun/create-file tmp path content)
              (assoc entry
                     :path path
                     :has-content true))))))

(defn render-pre-wrap
  "Handles common rendering task orchestration

  `render-paths-fn` takes two arguments: a fileset, and a map of task options.
  `options` is a map that must have a `:renderer` key, and any other keys
  that are required by `render-paths-fn`.

  Returns a boot `with-pre-wrap` result"
  [render-paths-fn options tracer]
  (let [tmp (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (let [render-paths (render-paths-fn fileset options)
            new-metadata (render-to-paths render-paths (:renderer options) tmp tracer)
            rm-files (keep #(boot/tmp-get fileset (-> % :entry :path)) (vals render-paths))]
        (-> fileset
            (boot/rm rm-files)
            (commit tmp)
            (perun/merge-meta new-metadata))))))

(defn- make-path
  [out-dir permalink path]
  (perun/create-filepath
   out-dir
   ; If permalink ends in slash, append index.html as filename
   (or (some-> permalink
               (string/replace #"/$" "/index.html")
               perun/url-to-path)
       path)))

(deftask render
  "Render individual pages for entries in perun data.

   The symbol supplied as `renderer` should resolve to a function
   which will be called with a map containing the following keys:
    - `:meta`, global perun metadata
    - `:entries`, all entries
    - `:entry`, the entry to be rendered

   Entries can optionally be filtered by supplying a function
   to the `filterer` option.

   Filename is determined as follows:
   If permalink is set for the file, it is used as the filepath.
   If permalink ends in slash, index.html is used as filename.
   If permalink is not set, the original filename is used with file extension set to html."
  [o out-dir  OUTDIR   str  "the output directory (default: \"public\")"
   _ filterer FILTER   code "predicate to use for selecting entries (default: `:has-content`)"
   r renderer RENDERER sym  "page renderer (fully qualified symbol which resolves to a function)"
   m meta     META     edn  "metadata to set on each entry"]
  (let [options (merge +render-defaults+ *opts*)]
    (letfn [(render-paths [fileset options]
              (let [entries (filter (:filterer options) (perun/get-meta fileset))
                    paths (reduce
                           (fn [result {:keys [path permalink] :as entry}]
                             (let [content (slurp (boot/tmp-file (boot/tmp-get fileset path)))
                                   new-path (make-path (:out-dir options) permalink path)
                                   meta-entry (merge meta entry)
                                   content-entry (assoc meta-entry :content content)
                                   render-data   {:meta    (perun/get-global-meta fileset)
                                                  :entries (vec entries)
                                                  :entry   content-entry}]
                               (perun/report-debug "render" "rendered page for path" path)
                               (assoc result new-path {:render-data render-data
                                                       :entry       meta-entry})))
                           {}
                           entries)]
                (perun/report-info "render" "rendered %s pages" (count paths))
                paths))]
      (render-pre-wrap render-paths options :io.perun/render))))

(defn- grouped-paths
  "Produces path maps of the shape required by `render-to-paths`, based
  on the provided `fileset` and `options`."
  [task-name fileset options]
  (let [global-meta (perun/get-global-meta fileset)
        grouper (:grouper options)
        paths (->> fileset
                   perun/get-meta
                   (filter (:filterer options))
                   grouper)]
    (if (seq paths)
      (reduce
       (fn [result [path {:keys [entries group-meta permalink]}]]
         (let [sorted      (sort-by (:sortby options) (:comparator options) entries)
               new-path    (make-path (:out-dir options) permalink path)
               new-entry   (merge group-meta {:path new-path
                                              :filename path})
               render-data {:meta    global-meta
                            :entry   new-entry
                            :entries (vec sorted)}]
           (perun/report-info task-name (str "rendered " task-name " " path))
           (assoc result new-path {:render-data render-data
                                   :entry       new-entry})))
       {}
       paths)
      (do
        (perun/report-info task-name (str task-name " found nothing to render"))
        []))))

(def ^:private +collection-defaults+
  {:out-dir "public"
   :filterer :has-content
   :sortby (fn [file] (:date-published file))
   :comparator (fn [i1 i2] (compare i2 i1))})

(deftask collection
  "Render single file for a collection of entries
   The symbol supplied as `renderer` should resolve to a function
   which will be called with a map containing the following keys:
    - `:meta`, global perun metadata
    - `:entry`, the metadata for this collection
    - `:entries`, all entries

   Entries can optionally be filtered by supplying a function
   to the `filterer` option.

   The `sortby` function can be used for ordering entries
   before rendering as well as rendering groups of entries to different pages."
  [o out-dir    OUTDIR     str  "the output directory"
   r renderer   RENDERER   sym  "page renderer (fully qualified symbol resolving to a function)"
   _ filterer   FILTER     code "predicate to use for selecting entries (default: `:has-content`)"
   s sortby     SORTBY     code "sort entries by function"
   c comparator COMPARATOR code "sort by comparator function"
   p page       PAGE       str  "collection result page path"
   m meta       META       edn  "metadata to set on each collection entry"]
  (let [options (merge +collection-defaults+
                       (dissoc *opts* :page)
                       (if-let [p (:page *opts*)]
                         {:grouper #(-> {p {:entries %
                                            :group-meta (:meta *opts*)}})}
                         {:grouper #(-> {"index.html" {:entries %
                                                       :group-meta (:meta *opts*)}})}))]
    (cond (not (fn? (:comparator options)))
          (u/fail "collection task :comparator option should implement Fn\n")
          (not (ifn? (:filterer options)))
          (u/fail "collection task :filterer option value should implement IFn\n")
          (not (ifn? (:sortby options)))
          (u/fail "collection task :sortby option value should implement IFn\n")
          :else
          (let [collection-paths (partial grouped-paths "collection")]
            (render-pre-wrap collection-paths options :io.perun/collection)))))

(def ^:private +assortment-defaults+
  {:out-dir "public"
   :filterer :has-content
   :sortby (fn [file] (:date-published file))
   :comparator (fn [i1 i2] (compare i2 i1))})

(deftask assortment
  "Render multiple collections
   The symbol supplied as `renderer` should resolve to a function
   which will be called with a map containing the following keys:
    - `:meta`, global perun metadata
    - `:entry`, the metadata for this collection
    - `:entries`, all entries

   The `grouper` function will be called with a seq containing the
   entries to be grouped, and it should return a map with keys that
   are filenames and values that are maps with the keys:
    - `:entries`: the entries for each collection
    - `:group-meta`: (optional) page metadata for this collection

   Entries can optionally be filtered by supplying a function
   to the `filterer` option.

   The `sortby` function can be used for ordering entries before rendering."
  [o out-dir    OUTDIR     str  "the output directory"
   r renderer   RENDERER   sym  "page renderer (fully qualified symbol resolving to a function)"
   g grouper    GROUPER    code "group posts function, keys are filenames, values are to-be-rendered entries"
   _ filterer   FILTER     code "predicate to use for selecting entries (default: `:has-content`)"
   s sortby     SORTBY     code "sort entries by function"
   c comparator COMPARATOR code "sort by comparator function"
   m meta       META       edn  "metadata to set on each collection entry"]
  (let [options (merge +assortment-defaults+
                       *opts*
                       (if-let [grouper* (:grouper *opts*)]
                         {:grouper (fn [metas]
                                     (->> metas
                                          grouper*
                                          (reduce
                                           (fn [paths [page data]]
                                             (assoc paths page
                                                    (update-in data [:group-meta]
                                                               #(merge (:meta *opts*) %))))
                                           {})))}
                         {:grouper #(-> {"index.html" {:entries %
                                                       :group-meta (:meta *opts*)}})}))]
    (cond (not (fn? (:comparator options)))
          (u/fail "assortment task :comparator option should implement Fn\n")
          (not (ifn? (:filterer options)))
          (u/fail "assortment task :filterer option value should implement IFn\n")
          (not (ifn? (:grouper options)))
          (u/fail "assortment task :grouper option value should implement IFn\n")
          (not (ifn? (:sortby options)))
          (u/fail "assortment task :sortby option value should implement IFn\n")
          :else
          (let [assortment-paths (partial grouped-paths "assortment")]
              (render-pre-wrap assortment-paths options :io.perun/assortment)))))

(deftask inject-scripts
  "Inject JavaScript scripts into html files.
   Use either filter to include only files matching or remove to
   include only files not matching regex."
   [s scripts JAVASCRIPT #{str}   "JavaScript files to inject as <script> tags in <head>."
    f filter  RE         #{regex} "Regexes to filter HTML files"
    r remove  RE         #{regex} "Regexes to blacklist HTML files with"]
   (let [pod  (create-pod [])
         prev (atom nil)
         out  (boot/tmp-dir!)
         filter (cond
                  filter #(boot/by-re filter %)
                  remove #(boot/by-re remove % true)
                  :else identity)]
     (fn [next-task]
       (fn [fileset]
         (let [files (->> fileset
                          (boot/fileset-diff @prev)
                          boot/input-files
                          filter
                          (boot/by-ext [".html"]))
                scripts-contents (->> fileset
                                      boot/input-files
                                      (boot/by-path scripts)
                                      (map (comp slurp boot/tmp-file)))]
           (doseq [file files
                   :let [new-file (io/file out (boot/tmp-path file))]]
             (perun/report-debug "inject-scripts" "injecting scripts" (boot/tmp-path file))
             (io/make-parents new-file)
             (pod/with-call-in @pod
               (io.perun.contrib.inject-scripts/inject-scripts
                 ~scripts-contents
                 ~(.getPath (boot/tmp-file file))
                 ~(.getPath new-file))))
           (perun/report-info "inject-scripts" "injected %s scripts into %s HTML files" (count scripts-contents) (count files)))
         (reset! prev fileset)
         (next-task (-> fileset (boot/add-resource out) boot/commit!))))))
