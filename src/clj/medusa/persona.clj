(ns clj.medusa.persona
  (:require [org.httpkit.client :as http-client]
            [cheshire.core :as json]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])
            [clj.medusa.db :as db]
            [clj.medusa.config :as config]))

(defn verify-assertion [assertion]
  (let [options {:query-params {:assertion assertion
                                ;;TODO: change audience to the real website
                                :audience (str (:hostname @config/state))}}
        {:keys [status body error]} @(http-client/post "https://verifier.login.persona.org/verify" options)]
    (let [{:keys [status email] :as response} (json/parse-string body true)]
      (when (= status "okay")
        ;; create the user if doesn't exist in the db
        (let [user (if-let [user (db/get-user email)]
                     user
                     (do
                       (db/add-user email)
                       (db/get-user email)))]
          (merge response {::user user}))))))

(defn credential-fn [{:keys [email status] :as authentication-map}]
  (when (= "okay" status)
    (merge authentication-map {:identity email
                               :roles #{::user}})))

(defn authorized? [ctx]
  (let [identity (friend/identity (:request ctx))
        authorized (friend/authorized? #{::user} identity)]
    {:identity identity}))

(defn user [ctx]
  (let [identity (friend/identity (:request ctx))
        current (:current identity)
        user (-> identity :authentications (get current) ::user)]
    user))

(defn workflow [uri request]
  (if (and (= (:uri request) uri)
           (= (:request-method request) :get))
    (let [assertion (get-in request [:query-params "assertion"])
          verification (verify-assertion assertion)
          authentication-map (credential-fn verification)]
      (workflows/make-auth authentication-map {::friend/redirect-on-auth? false
                                               ::friend/workflow :mozilla-persona}))))
