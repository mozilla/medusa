(ns clj.medusa.changesets
  (:require [clj-http.lite.client :as client]
            [pl.danieljanus.tagsoup :as tagsoup]))

;; This library finds changesets given build dates (dates of the form YYYY-MM-DD)
;; or build IDs (exact build timestamps of the form YYYYMMDDhhmmss).
;;
;; Example: obtaining a link to the changesets for build date 2015-05-03:
;;
;;     (println (find-build-changeset (find-date-buildid "2015-05-03" "mozilla-central") "mozilla-central"))
;;
;; Example: obtaining a link to changesets for buildid 20150503030209
;;
;;     (println (find-build-changeset "20150503030209" "mozilla-central"))

(declare elements-by-tag-name)

(defn- split-buildid
  "Splits a buildid `buildid` into a dictionary with the date/time components as entries."
  [buildid]
  {:y (Integer/parseInt (subs buildid 0 4)) :m (Integer/parseInt (subs buildid 4 6)) :d (Integer/parseInt (subs buildid 6 8))
   :hour (Integer/parseInt (subs buildid 8 10)) :min (Integer/parseInt (subs buildid 10 12)) :sec (Integer/parseInt (subs buildid 12 14))})

(defn- list-elements-by-tag-name
  "Obtains a list of all tags with tag name `tag-name` in `children`, a list of DOM elements in the form outputted by Tagsoup."
  [children tag-name]
  (cond (empty? children) '()
        (vector? (first children)) (concat (elements-by-tag-name (first children) tag-name)
                                           (list-elements-by-tag-name (rest children) tag-name))
        :else '()))

(defn- elements-by-tag-name
  "Obtains a list of all tags with tag name `tag-name` in `tag`, a DOM in the form outputted by Tagsoup."
  [tag tag-name]
  {:pre [(vector? tag) (keyword? tag-name)]}
  (if (= (first tag) :a)
    (cons tag (list-elements-by-tag-name (rest (rest tag)) tag-name))
    (list-elements-by-tag-name (rest (rest tag)) tag-name)))

(defn- find-build-dir-revision
  "Finds the hg revision associated with the build dir URL `build-dir-url`."
  [build-dir-url]
  (let [response (tagsoup/parse build-dir-url)
        links (elements-by-tag-name response :a)
        text-file-links (filter #(re-find #"^firefox.*win32\.txt$" (get % 2)) links)]
    (assert (= (count text-file-links) 1) "Could not find revision ID text file")
    (let [revision-file-url (str build-dir-url (:href (second (first text-file-links))))
          revision-file (:body (client/get revision-file-url))
          revision (re-find #"https:\/\/hg\.mozilla.*([0-9a-f]{12})$" revision-file)]
      (second revision))))

(defn- find-build-revision
  "Finds the hg revision of associated with the buildid `buildid` on channel `channel`."
  [buildid channel]
  (let [p (split-buildid buildid)
        build-dir-url (format "https://archive.mozilla.org/pub/mozilla.org/firefox/nightly/%02d/%02d/%02d-%02d-%02d-%02d-%02d-%02d-%s/"
                    (:y p) (:m p) (:y p) (:m p) (:d p) (:hour p) (:min p) (:sec p) channel)]
    (find-build-dir-revision build-dir-url)))

(defn- find-preceding-build-revision
  "Find the first build made before buildid `buildid` on channel `channel`, and get its associated revision."
  [buildid channel]
  (let [p (split-buildid buildid)

        ;; Obtain a list of links to build directories in the desired channel
        build-dirs-url (format "https://archive.mozilla.org/pub/mozilla.org/firefox/nightly/%02d/%02d/" (:y p) (:m p))
        target (format "%02d-%02d-%02d-%02d-%02d-%02d-%s/" (:y p) (:m p) (:d p) (:hour p) (:min p) (:sec p) channel)
        response (tagsoup/parse build-dirs-url)
        links (elements-by-tag-name response :a)
        build-dirs-suffix (format "-%s/" channel)
        build-dirs-links (filter #(.endsWith (get % 2) build-dirs-suffix) links)

        ;; Find the index of the link before the link having the specified buildid
        target-link-index (dec (first (keep-indexed #(when (= (clojure.string/trim (get %2 2)) target) %1) build-dirs-links)))]
    (if (= target-link-index -1) ; check if we have the directory of previous build in the same month's folder
        (let [year (if (= (:m p) 0) (dec (:y p)) (:y p)) ; the build is the first one in that month, use the last build of the previous month's folder
              month (if (= (:m p) 0) 12 (dec (:m p)))

              ;; Obtain the last link to build directories in the desired channel in the previous month's folder, which is the build dir of the last build in the previous month
              prev-build-dirs-url (format "https://archive.mozilla.org/pub/mozilla.org/firefox/nightly/%02d/%02d/" year month)
              response (tagsoup/parse prev-build-dirs-url)
              links (elements-by-tag-name response :a)
              target-link (last (filter #(.endsWith (get % 2) build-dirs-suffix) links))
              build-dir-url (str prev-build-dirs-url (:href (second target-link)))]
          (find-build-dir-revision build-dir-url))
        (let [target-link (nth build-dirs-links target-link-index) ; get the build directory and the revision from the link
              build-dir-url (str build-dirs-url (:href (second target-link)))]
          (find-build-dir-revision build-dir-url)))))

(defn find-build-changeset
  "Finds the changesets corresponding to the build with buildid `buildid`, which is a URL for a page with a list of all new changesets that went into that build."
  [buildid channel]
  (let [from-revision (find-preceding-build-revision buildid channel)
        to-revision (find-build-revision buildid channel)]
    (format "https://hg.mozilla.org/%s/pushloghtml?fromchange=%s&tochange=%s"
            channel from-revision to-revision)))

(defn find-date-buildid
  "Determines the buildid (a date-time of the form YYYYMMDDhhmmss) corresponding to a given build date `date` (a date of the form YYYY-MM-DD) on the channel string `channel`, generally mozilla-central."
  [date channel]
  (let [[_ year month day] (re-find #"^(\d{4})-(\d{2})-(\d{2})$" date)
        build-dirs-suffix (format "-%s/" channel)
        build-dirs-url (format "https://archive.mozilla.org/pub/mozilla.org/firefox/nightly/%s/%s/" year month)
        response (tagsoup/parse build-dirs-url)
        links (elements-by-tag-name response :a)
        build-dirs-links (filter #(.endsWith (get % 2) build-dirs-suffix) links)
        target-link (first (filter #(.startsWith (get % 2) date) build-dirs-links))
        [_ hour minute second] (re-find #"^\d{4}-\d{2}-\d{2}-(\d{2})-(\d{2})-(\d{2})" (get target-link 2))]
    (str year month day hour minute second)))
