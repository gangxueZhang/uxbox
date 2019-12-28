;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.images
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.main.store :as st]
   [uxbox.main.repo.core :as rp]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.data :refer (jscoll->vec)]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.time :as ts]
   [uxbox.util.spec :as us]
   [uxbox.util.router :as r]
   [uxbox.util.files :as files]))

;; --- Specs

(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::modified-at inst?)
(s/def ::created-at inst?)
(s/def ::mimetype string?)
(s/def ::thumbnail us/url-str?)
(s/def ::id uuid?)
(s/def ::url us/url-str?)
(s/def ::collection-id (s/nilable ::us/uuid))
(s/def ::user-id ::us/uuid)

(s/def ::collection-entity
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user-id]))

(s/def ::image-entity
  (s/keys :opt-un [::collection-id]
          :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::created-at
                   ::modified-at
                   ::mimetype
                   ::thumbnail
                   ::url
                   ::user-id]))

;; --- Collections Fetched

(defn collections-fetched
  [items]
  (s/assert (s/every ::collection-entity) items)
  (ptk/reify ::collections-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state {:keys [id user] :as item}]
                (let [type (if (uuid/zero? (:user-id item)) :builtin :own)
                      item (assoc item :type type)]
                  (assoc-in state [:images-collections id] item)))
              state
              items))))

;; --- Fetch Color Collections

(def fetch-collections
  (ptk/reify ::fetch-collections
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query! :images-collections)
           (rx/map collections-fetched)))))

;; --- Collection Created

(defn collection-created
  [item]
  (s/assert ::collection-entity item)
  (ptk/reify ::collection-created
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [id] :as item} (assoc item :type :own)]
        (update state :images-collections assoc id item)))))

;; --- Create Collection

(def create-collection
  (ptk/reify ::create-collection
    ptk/WatchEvent
    (watch [_ state s]
      (let [data {:name (tr "ds.default-library-title" (gensym "c"))}]
        (->> (rp/mutation! :create-image-collection data)
             (rx/map collection-created))))))

;; --- Collection Updated

(defrecord CollectionUpdated [item]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:images-collections (:id item)]  merge item)))

(defn collection-updated
  [item]
  {:pre [(us/valid? ::collection-entity item)]}
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [item (get-in state [:images-collections id])]
      (->> (rp/mutation! :update-images-collection item)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:images-collections id :name] name))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [id name]
  (RenameCollection. id name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (update state :images-collections dissoc id))

  ptk/WatchEvent
  (watch [_ state s]
    (let [type (get-in state [:dashboard :images :type])]
      (->> (rp/mutation! :delete-images-collection {:id id})
           (rx/map #(rt/nav :dashboard/images nil {:type type}))))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Image Created

(defn image-created
  [item]
  (s/assert ::image-entity item)
  (ptk/reify ::image-created
    ptk/UpdateEvent
    (update [_ state]
      (update state :images assoc (:id item) item))))

;; --- Create Image

(def allowed-file-types #{"image/jpeg" "image/png"})

(defn create-images
  ([id files] (create-images id files identity))
  ([id files on-uploaded]
   (s/assert (s/nilable ::us/uuid) id)
   (s/assert fn? on-uploaded)
   (ptk/reify ::create-images
     ptk/UpdateEvent
     (update [_ state]
       (assoc-in state [:dashboard :images :uploading] true))

     ptk/WatchEvent
     (watch [_ state stream]
       (letfn [(image-size [file]
                 (->> (files/get-image-size file)
                      (rx/map (partial vector file))))
               (allowed-file? [file]
                 (contains? allowed-file-types (.-type file)))
               (finalize-upload [state]
                 (assoc-in state [:dashboard :images :uploading] false))
               (prepare [[file [width height]]]
                 (cond-> {:name (.-name file)
                          :mimetype (.-type file)
                          :id (uuid/random)
                          :file file
                          :width width
                          :height height}
                   id (assoc :collection-id id)))]
         (->> (rx/from files)
              (rx/filter allowed-file?)
              (rx/mapcat image-size)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :create-image %))
              (rx/reduce conj [])
              (rx/do #(st/emit! finalize-upload))
              (rx/do on-uploaded)
              (rx/mapcat identity)
              (rx/map image-created)))))))

;; --- Update Image

(defn persist-image
  [id]
  {:pre [(uuid? id)]}
  (ptk/reify ::persist-image
    ptk/WatchEvent
    (watch [_ state stream]
      (let [data (get-in state [:images id])]
        (->> (rp/mutation! :update-image data)
             (rx/ignore))))))

;; --- Images Fetched

(defn images-fetched
  [items]
  (s/assert (s/every ::image-entity) items)
  (ptk/reify ::images-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state {:keys [id] :as image}]
                (assoc-in state [:images id] image))
              state
              items))))

;; --- Fetch Images

(defn fetch-images
  "Fetch a list of images of the selected collection"
  [id]
  (s/assert (s/nilable ::us/uuid) id)
  (ptk/reify ::fetch-images
    ptk/WatchEvent
    (watch [_ state s]
      (let [params (cond-> {} id (assoc :collection-id id))]
        (->> (rp/query! :images-by-collection params)
             (rx/map images-fetched))))))

;; --- Fetch Image

(declare image-fetched)

(defrecord FetchImage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [existing (get-in state [:images id])]
      (if existing
        (rx/empty)
        (->> (rp/query! :image-by-id {:id id})
             (rx/map image-fetched)
             (rx/catch rp/client-error? #(rx/empty)))))))

(defn fetch-image
  "Conditionally fetch image by its id. If image
  is already loaded, this event is noop."
  [id]
  {:pre [(uuid? id)]}
  (FetchImage. id))

;; --- Image Fetched

(defrecord ImageFetched [image]
  ptk/UpdateEvent
  (update [_ state]
    (let [id (:id image)]
      (update state :images assoc id image))))

(defn image-fetched
  [image]
  {:pre [(map? image)]}
  (ImageFetched. image))

;; --- Delete Images

(defrecord DeleteImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (update :images dissoc id)
        (update-in [:dashboard :images :selected] disj id)))

  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/mutation! :delete-image {:id id})
         (rx/ignore))))

(defn delete-image
  [id]
  {:pre [(uuid? id)]}
  (DeleteImage. id))

;; --- Rename Image

(defrecord RenameImage [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:images id :name] name))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-image id))))

(defn rename-image
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameImage. id name))

;; --- Select image

(defrecord SelectImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images :selected] conj id)))

(defrecord DeselectImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images :selected] disj id)))

(defrecord ToggleImageSelection [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/of
       (if (selected id)
         (DeselectImage. id)
         (SelectImage. id))))))

(defn deselect-image
  [id]
  {:pre [(uuid? id)]}
  (DeselectImage. id))

(defn toggle-image-selection
  [id]
  (ToggleImageSelection. id))

;; --- Copy Selected Image

(defrecord CopySelected [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/merge
       (->> (rx/from selected)
            (rx/flat-map #(rp/mutation! :copy-image {:id % :collection-id id}))
            (rx/map image-created))
       (->> (rx/from selected)
            (rx/map deselect-image))))))

(defn copy-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (CopySelected. id))

;; --- Move Selected Image

(defrecord MoveSelected [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:dashboard :images :selected])]
      (reduce (fn [state image]
                (assoc-in state [:images image :collection] id))
              state
              selected)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/merge
       (->> (rx/from selected)
            (rx/map persist-image))
       (->> (rx/from selected)
            (rx/map deselect-image))))))

(defn move-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (MoveSelected. id))

;; --- Delete Selected

(defrecord DeleteSelected []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (->> (rx/from selected)
           (rx/map delete-image)))))

(defn delete-selected
  []
  (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter edition]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images] merge
               {:edition edition}
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter edition]
      :or {edition false}
      :as opts}]
  (UpdateOpts. order filter edition))
