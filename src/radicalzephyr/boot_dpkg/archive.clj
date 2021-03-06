(ns radicalzephyr.boot-dpkg.archive
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import
   (java.io BufferedOutputStream
            File
            FileInputStream
            FileOutputStream)
   (java.nio.file Files
                  LinkOption
                  Path)
   (java.nio.file.attribute PosixFileAttributes
                            PosixFilePermission)
   (org.apache.commons.io IOUtils)
   (org.apache.commons.compress.archivers.ar ArArchiveOutputStream
                                             ArArchiveEntry)
   (org.apache.commons.compress.archivers.tar TarArchiveOutputStream
                                              TarArchiveEntry)
   (org.apache.commons.compress.compressors.gzip GzipCompressorOutputStream)
   (org.apache.commons.compress.compressors.xz XZCompressorOutputStream)))

(defmacro with-archive-entry [archive entry & body]
  `(let [archive# ~archive
         entry# ~entry]
     (.putArchiveEntry archive# entry#)
     (try
       ~@body
       (finally
         (.closeArchiveEntry archive#)))))

(defn- in-debian? [[_ path]]
  (.startsWith path "DEBIAN/"))

(defn- strip-debian [[file path]]
  [file (str/replace path #"DEBIAN/" "")])

(defn- file->path-str [root-dir file]
  (let [rel-path (.relativize root-dir (.toPath file))
        suffix (if (.isFile file) "" "/")]
    (str rel-path suffix)))

(def ^:private skip-files
  #{"DEBIAN"
    "control.tar.gz"
    "data.tar.xz"})

(defn- split-files [in-dir]
  (let [root-dir (.toPath in-dir)
        relative-paths (->> (file-seq in-dir)
                            (drop 1)
                            (map (juxt identity #(file->path-str root-dir %)))
                            (remove #(contains? skip-files (second %))))]
    [(->> relative-paths
          (filter in-debian?)
          (drop 1)
          (map strip-debian))
     (remove in-debian? relative-paths)]))

(defn create-debian-binary-file! [ar-out]
  (let [bytes (.getBytes "2.0\n")]
    (with-archive-entry ar-out (ArArchiveEntry. "debian-binary" (count bytes))
      (.write ar-out bytes))))

(defn- write-entry-to-stream! [tar-out entry file]
  (with-archive-entry tar-out entry
    (when (.isFile file)
      (with-open [file-stream (FileInputStream. file)]
        (IOUtils/copy file-stream tar-out)))))

(def ^:private
  maintainer-script-names
  #{"prerm" "postrm"
    "preinst" "postinst"})

(defn create-control-tar! [tmp-dir paths]
  (let [tar-file (io/file tmp-dir "control.tar.gz")]
    (with-open [tar-out (-> tar-file
                            FileOutputStream.
                            BufferedOutputStream.
                            GzipCompressorOutputStream.
                            TarArchiveOutputStream.)]
      (doseq [[file archive-name] paths
              :let [entry (TarArchiveEntry. file archive-name)]]
        (when (contains? maintainer-script-names archive-name)
          (.setMode entry 0755))
        (write-entry-to-stream! tar-out entry file)))
    tar-file))

(def ^:private
  permission->int
  {PosixFilePermission/OWNER_READ 0400
   PosixFilePermission/OWNER_WRITE 0200
   PosixFilePermission/OWNER_EXECUTE 0100

   PosixFilePermission/GROUP_READ 040
   PosixFilePermission/GROUP_WRITE 020
   PosixFilePermission/GROUP_EXECUTE 010

   PosixFilePermission/OTHERS_READ 04
   PosixFilePermission/OTHERS_WRITE 02
   PosixFilePermission/OTHERS_EXECUTE 01})

(defn- get-int-file-permissions [^Path file]
  (let [attrs (Files/readAttributes file
                                    PosixFileAttributes
                                    (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
    (->> (.permissions attrs)
         (map permission->int)
         (reduce +))))

(defn- get-chown [chowns path]
  (some (fn [[prefix chown]]
          (when (str/starts-with? path prefix)
            chown))
        chowns))

(defn- set-owners! [entry user group]
  (doto entry
    (.setUserName user)
    (.setGroupName group))
  nil)

(defn create-data-tar! [tmp-dir paths chowns]
  (let [tar-file (io/file tmp-dir "data.tar.xz")]
    (with-open [tar-out (-> tar-file
                            FileOutputStream.
                            BufferedOutputStream.
                            XZCompressorOutputStream.
                            TarArchiveOutputStream.)]
      (doseq [[file archive-name] paths
              :let [entry (TarArchiveEntry. file archive-name)]]
        (.setMode entry (get-int-file-permissions (.toPath file)))
        (when-let [[user group] (get-chown chowns archive-name)]
          (set-owners! entry user group))
        (write-entry-to-stream! tar-out entry file)))
    tar-file))

(defn create-deb-package [in-dir out-file chowns]
  (let [in-dir (io/file in-dir)
        root-path (.toPath in-dir)
        out-file (io/file out-file)
        [control-files data-files] (split-files in-dir)
        control-tar-file (create-control-tar! in-dir control-files)
        data-tar-file (create-data-tar! in-dir data-files chowns)]
    (with-open [ar-out (ArArchiveOutputStream. (FileOutputStream. out-file))]
      (create-debian-binary-file! ar-out)
      (with-archive-entry ar-out (ArArchiveEntry. "control.tar.gz" (.length control-tar-file))
        (with-open [control-tar-stream (FileInputStream. control-tar-file)]
          (IOUtils/copy control-tar-stream ar-out)))
      (with-archive-entry ar-out (ArArchiveEntry. "data.tar.xz" (.length data-tar-file))
        (with-open [data-tar-stream (FileInputStream. data-tar-file)]
          (IOUtils/copy data-tar-stream ar-out))))))
