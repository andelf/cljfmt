(ns cljfmt.main
  "Functionality to apply formatting to a given project."
  (:require [cljfmt.core :as cljfmt]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.stacktrace :as st]
            [clojure.tools.cli :as cli]
            [cljfmt.diff :as diff]))

(def default-config
  {:project-root "."
   :file-pattern #"\.clj[csx]?$"
   :paths []
   :ansi? true
   :indentation? true
   :insert-missing-whitespace? true
   :remove-surrounding-whitespace? true
   :remove-trailing-whitespace? true
   :remove-consecutive-blank-lines? true
   :indents cljfmt/default-indents
   :alias-map {}})

(defn- abort [& msg]
  (binding [*out* *err*]
    (when (seq msg)
      (apply println msg))
    (System/exit 1)))

(defn- warn [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn- relative-path [dir file]
  (-> (.toURI dir)
      (.relativize (.toURI file))
      (.getPath)))

(defn- grep [re dir]
  (filter #(re-find re (relative-path dir %)) (file-seq (io/file dir))))

(defn- find-files [{:keys [file-pattern]} f]
  (let [f (io/file f)]
    (when-not (.exists f) (abort "No such file:" (str f)))
    (if (.isDirectory f)
      (grep file-pattern f)
      [f])))

(defn- reformat-string [options s]
  (cljfmt/reformat-string s options))

(defn- project-path [{:keys [project-root]} file]
  (-> project-root io/file (relative-path (io/file file))))

(defn- format-diff
  ([options file]
   (let [original (slurp (io/file file))]
     (format-diff options file original (reformat-string options original))))
  ([options file original revised]
   (let [filename (project-path options file)
         diff     (diff/unified-diff filename original revised)]
     (if (:ansi? options)
       (diff/colorize-diff diff)
       diff))))

(def ^:private zero-counts {:okay 0, :incorrect 0, :error 0})

(defn- check-one [options file]
  (let [original (slurp file)
        status   {:counts zero-counts :file file}]
    (try
      (let [revised (reformat-string options original)]
        (if (not= original revised)
          (-> status
              (assoc-in [:counts :incorrect] 1)
              (assoc :diff (format-diff options file original revised)))
          (assoc-in status [:counts :okay] 1)))
      (catch Exception ex
        (-> status
            (assoc-in [:counts :error] 1)
            (assoc :exception ex))))))

(defn- print-stack-trace [ex]
  (binding [*out* *err*]
    (st/print-stack-trace ex)))

(defn- print-file-status [options status]
  (let [path (project-path options (:file status))]
    (when-let [ex (:exception status)]
      (warn "Failed to format file:" path)
      (print-stack-trace ex))
    (when-let [diff (:diff status)]
      (warn path "has incorrect formatting")
      (warn diff))))

(defn- exit [counts]
  (when-not (zero? (:error counts 0))
    (System/exit 2))
  (when-not (zero? (:incorrect counts 0))
    (System/exit 1)))

(defn- print-final-count [counts]
  (let [error     (:error counts 0)
        incorrect (:incorrect counts 0)]
    (when-not (zero? error)
      (warn error "file(s) could not be parsed for formatting"))
    (when-not (zero? incorrect)
      (warn incorrect "file(s) formatted incorrectly"))
    (when (and (zero? incorrect) (zero? error))
      (println "All source files formatted correctly"))))

(defn- merge-counts
  ([]    zero-counts)
  ([a]   a)
  ([a b] (merge-with + a b)))

(defn check
  "Checks that the Clojure files contained in `paths` follow the formatting
  (as per `options`)."
  ([paths]
   (check paths default-config))
  ([paths options]
   (let [counts (transduce
                 (comp (mapcat (partial find-files options))
                       (map (partial check-one options))
                       (map (fn [status]
                              (print-file-status options status)
                              (:counts status))))
                 (completing merge-counts)
                 paths)]
     (print-final-count counts)
     (exit counts))))

(defn fix
  "Applies the formatting (as per `options`) to the Clojure files
  contained in `paths`."
  ([paths]
   (fix paths default-config))
  ([paths options]
   (let [files (mapcat (partial find-files options) paths)]
     (doseq [^java.io.File f files]
       (let [original (slurp f)]
         (try
           (let [revised (reformat-string options original)]
             (when (not= original revised)
               (println "Reformatting" (project-path options f))
               (spit f revised)))
           (catch Exception e
             (warn "Failed to format file:" (project-path options f))
             (binding [*out* *err*]
               (print-stack-trace e)))))))))

(def ^:private cli-file-reader
  (comp edn/read-string slurp diff/to-absolute-path))

(def ^:private cli-options
  [[nil "--help"]
   [nil "--file-pattern FILE_PATTERN"
    :default (:file-pattern default-config)
    :parse-fn re-pattern]
   [nil "--indents INDENTS_PATH"
    :parse-fn cli-file-reader]
   [nil "--alias-map ALIAS_MAP_PATH"
    :parse-fn cli-file-reader]
   [nil "--project-root PROJECT_ROOT"
    :default (:project-root default-config)]
   [nil "--[no-]ansi"
    :default (:ansi? default-config)
    :id :ansi?]
   [nil "--[no-]indentation"
    :default (:indentation? default-config)
    :id :indentation?]
   [nil "--[no-]remove-surrounding-whitespace"
    :default (:remove-surrounding-whitespace? default-config)
    :id :remove-surrounding-whitespace?]
   [nil "--[no-]remove-trailing-whitespace"
    :default (:remove-trailing-whitespace? default-config)
    :id :remove-trailing-whitespace?]
   [nil "--[no-]insert-missing-whitespace"
    :default (:insert-missing-whitespace? default-config)
    :id :insert-missing-whitespace?]
   [nil "--[no-]remove-consecutive-blank-lines"
    :default (:remove-consecutive-blank-lines? default-config)
    :id :remove-consecutive-blank-lines?]])

(defn- command-name []
  (or (System/getProperty "sun.java.command") "cljfmt"))

(defn -main [& args]
  (let [parsed-opts   (cli/parse-opts args cli-options)
        [cmd & paths] (:arguments parsed-opts)
        options       (merge default-config (:options parsed-opts))
        paths         (or (seq paths) ["src" "test"])]
    (if (:errors parsed-opts)
      (abort (:errors parsed-opts))
      (if (or (nil? cmd) (:help options))
        (do (println "cljfmt [OPTIONS] COMMAND [PATHS ...]")
            (println (:summary parsed-opts)))
        (case cmd
          "check" (check paths options)
          "fix"   (fix paths options)
          (abort "Unknown cljfmt command:" cmd))))))