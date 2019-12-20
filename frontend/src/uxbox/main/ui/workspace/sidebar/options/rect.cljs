;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer [parse-int parse-float read-string]]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :refer [precision-or-0]]))

(declare on-size-change)
(declare on-rotation-change)
(declare on-position-change)
(declare on-proportion-lock-change)

(mf/defc measures
  [{:keys [menu shape] :as props}]
  (let [size (geom/size shape)]
    [:div.element-set
     [:div.element-set-title (tr "element.measures")]
     [:div.element-set-content
      [:span (tr "ds.size")]

      ;; WIDTH & HEIGHT
      [:div.row-flex
       [:div.input-element.pixels
        [:input.input-text {:placeholder (tr "ds.width")
                            :type "number"
                            :min "0"
                            :value (precision-or-0 (:width size) 2)}]]

       [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                        :on-click #(on-proportion-lock-change % shape)}
        (if (:proportion-lock shape) i/lock i/unlock)]

       [:div.input-element.pixels
        [:input.input-text {:placeholder (tr "ds.height")
                            :type "number"
                            :min "0"
                            :value (precision-or-0 (:height size) 2)}]]]

      ;; POSITION
      [:span (tr "ds.position")]
      [:div.row-flex
       [:div.input-element.pixels
        [:input.input-text {:placeholder "x"
                            :type "number"
                            :value (precision-or-0 (:x1 shape 0) 2)}]]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "y"
                            :type "number"
                            :value (precision-or-0 (:y1 shape 0) 2)}]]]

       ;; ROTATION
      [:span (tr "ds.rotation")]
      [:div.row-flex
       [:input.slidebar {:type "range"
                         :min 0
                         :max 360
                         ;; :on-change #(on-rotation-change % shape)
                         :value (:rotation shape 0)}]]

      [:div.row-flex
       [:div.input-element.degrees
        [:input.input-text {:placeholder ""
                            :type "number"
                            :min 0
                            :max 360
                            :on-change #(on-rotation-change % shape)
                            :value (precision-or-0 (:rotation shape "0") 2)}]]
       [:input.input-text {:style {:visibility "hidden"}}]]]]))

;; (defn- on-size-change
;;   [event shape attr]
;;   (let [value (-> (dom/event->value event)
;;                   (parse-int 0))]
;;     (st/emit! (udw/update-dimensions (:id shape) {attr value}))))

;; (defn- on-rotation-change
;;   [event shape]
;;   (let [value (dom/event->value event)
;;         value (parse-int value 0)]
;;     (st/emit! (udw/update-shape-attrs (:id shape) {:rotation value}))))

;; (defn- on-position-change
;;   [event shape attr]
;;   (let [value (-> (dom/event->value event)
;;                   (parse-int nil))
;;         point (gpt/point {attr value})]
;;     (st/emit! (udw/update-position (:id shape) point))))

;; (defn- on-proportion-lock-change
;;   [event shape]
;;   (if (:proportion-lock shape)
;;     (st/emit! (udw/unlock-proportions (:id shape)))
;;     (st/emit! (udw/lock-proportions (:id shape)))))


  ;; :rect   [::rect-measures ::fill ::stroke]

(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures {:shape shape}]
   [:& fill-menu {:shape shape}]
   [:& stroke-menu {:shape shape}]])