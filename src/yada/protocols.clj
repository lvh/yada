;; Copyright © 2015, JUXT LTD.

(ns yada.protocols
  (:require
   [manifold.deferred :as d]
   [manifold.stream :refer (->source transform)]
   [clojure.tools.logging :refer :all]
   [clojure.core.async.impl.protocols :as aip])
  (import (clojure.core.async.impl.protocols ReadPort)))

(defprotocol Callbacks
  (service-available? [_] "Return whether the service is available")
  (known-method? [_ method])

  (request-uri-too-long? [_ uri])

  (allowed-methods [_ ctx] "Return a set of allowed methods")
  (resource [_ req] "Return the resource. Typically this is just the resources's meta-data and does not include the body.")
  (state [_ ctx] "Return the state, if not available in the resource.")
  (last-modified [_ ctx] "Return the date that the resource was last modified.")
  (body [_ ctx] "Return a representation of the resource. See yada documentation for the structure of the ctx argument.")
  (produces [_] "Return the content-types, as a set, that the resource can produce")
  (produces-from-body [_] "If produces yields nil, try to extract from body")
  (status [_] "Override the response status")
  (headers [_] "Override the response headers")
  (post [_ ctx] "POST to the resource")
  (interpret-post-result [_ ctx] "Return the request context, according to the result of post")

  (authorize [_ ctx] "Authorize the request. When truthy, authorization is called with the value and used as the :authorization entry of the context, otherwise assumed unauthorized.")
  (authorization [o] "Given the result of an authorize call, a truthy value will be added to the context.")

  (format-event [_] "Format an individual event")

  (allow-origin [_ ctx] "If another origin (other than the resource's origin) is allowed, return the the value of the Access-Control-Allow-Origin header to be set on the response")
  )

(extend-protocol Callbacks
  Boolean
  (service-available? [b] [b {}])
  (known-method? [b method] [b {}])
  (request-uri-too-long? [b _] [b {}])
  (resource [b req] (if b {} false))
  (interpret-post-result [b ctx]
    (if b ctx (throw (ex-info "Failed to process POST" {}))))
  (authorize [b ctx] b)
  (authorization [b] nil)
  (allow-origin [b _] (when b "*"))

  clojure.lang.Fn
  (service-available? [f]
    (let [res (f)]
      (if (d/deferrable? res)
        (d/chain res #(service-available? %))
        (service-available? res))))

  (known-method? [f method] (known-method? (f method) method))
  (request-uri-too-long? [f uri] (request-uri-too-long? (f uri) uri))

  (resource [f req]
    (let [res (f req)]
      (if (d/deferrable? res)
        (d/chain res #(resource % req))
        (resource res req))))

  (state [f ctx]
    (let [res (f ctx)]
      (cond
        (satisfies? aip/ReadPort res)
        (state res ctx)

        ;; Deferrable
        (d/deferrable? res)
        (d/chain res #(state % ctx))

        :otherwise
        (state res ctx))))

  (last-modified [f ctx]
    (let [res (f ctx)]
      (if (d/deferrable? res)
        (d/chain res #(last-modified % ctx))
        (last-modified res ctx))))

  (body [f ctx]
    (let [res (f ctx)]
      (cond
        ;; If this is something we can take from, in the core.async
        ;; sense, then call body again. We need this clause here
        ;; because: (satisfies? d/Deferrable (a/chan)) => true, so
        ;; (deferrable?  (a/chan) is (consequently) true too.
        (satisfies? aip/ReadPort res)
        (body res ctx)

        ;; Deferrable
        (d/deferrable? res)
        (d/chain res #(body % ctx))

        :otherwise
        (body res ctx))))

  (produces [f] (f))
  (produces-from-body [f] nil)

  (post [f ctx]
    (f ctx))

  (authorize [f ctx] (f ctx))

  (allow-origin [f ctx] (f ctx))

  String
  (body [s _] s)
  (produces-from-body [s] nil)
  (interpret-post-result [s ctx]
    (assoc ctx :location s))
  (format-event [ev] [(format "data: %s\n" ev)])

  Number
  (service-available? [n] [false {:headers {"retry-after" n}}])
  (request-uri-too-long? [n uri]
    (request-uri-too-long? (> (.length uri) n) uri))
  (status [n] n)
  (last-modified [l _] (java.util.Date. l))

  java.util.Set
  (known-method? [set method]
    [(contains? set method) {}])
  (produces [set] set)

  clojure.lang.Keyword
  (known-method? [k method]
    (known-method? #{k} method))

  java.util.Map
  (allowed-methods [m _] (set (keys m)))
  (resource [m _] m)
  (state [m _] m)
  (body [m ctx]
    ;; Maps indicate keys are exact content-types
    ;; For matching on content-type, use a vector of vectors (TODO)
    (when-let [delegate (get m (get-in ctx [:response :content-type]))]
      (body delegate ctx)))
  (produces-from-body [m] (keys m))
  (headers [m] m)
  (interpret-post-result [m _] m)
  (format-event [ev] )

  clojure.lang.PersistentVector
  (produces [v] (produces (set v)))
  (body [v ctx] v)

  java.util.Date
  (last-modified [d _] d)

  nil
  ;; These represent the handler defaults, all of which can be
  ;; overridden by providing non-nil arguments
  (service-available? [_] true)
  (known-method? [_ method]
    (known-method? #{:get :put :post :delete :options :head} method))
  (request-uri-too-long? [_ uri]
    (request-uri-too-long? 4096 uri))
  (resource [_ _] nil)
  (state [_ _] nil)
  (body [_ _] nil)
  (post [_ _] nil)
  (produces [_] nil)
  (produces-from-body [_] nil)
  (status [_] nil)
  (headers [_] nil)
  (interpret-post-result [_ ctx] nil)
  (allow-origin [_ _] nil)

  ReadPort
  (state [port ctx] (->source port))
  (body [port ctx] (->source port))

  Object
  (authorization [o] o)

  )
