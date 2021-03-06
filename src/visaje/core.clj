(ns visaje.core
  (:use [vmfest.virtualbox.virtualbox :only [find-medium open-medium]]
        [vmfest.virtualbox.session :only [with-vbox]]
        [vmfest.manager :only [instance start get-machine-attribute
                               send-keyboard stop destroy power-down
                               make-disk-immutable new-image]]
        [clojure.string :only [blank?]]

        [clojure.java.io :only (input-stream)]
        [clojure.pprint :only (pprint)])
  (:require [clojure.tools.logging :as log]
            [clj-ssh.ssh :as ssh]))


(defn install-machine-spec [disk-location os-iso-location vbox-iso-location
                            memory-size]
  {:cpu-count 1,
   :network
   [{:attachment-type :nat}
    {:attachment-type :host-only :host-only-interface "vboxnet0"}],
   :storage
   [{:devices [{:device-type :hard-disk
                :location disk-location
                :attachment-type :normal}
               nil
               {:device-type :dvd :location os-iso-location}
               {:device-type :dvd :location vbox-iso-location}],
     :name "IDE Controller",
     :bus :ide}],
   :memory-size memory-size})

(defn wait-for
  [exit?-fn wait-interval timeout]
  (let [timeout (+ (System/currentTimeMillis) timeout)]
    (loop []
      (let [result (exit?-fn)]
        (if (nil? result)
          (do (Thread/sleep wait-interval)
              (recur))
          result)))))

(defn fully-installed? [ip user password]
  (ssh/with-ssh-agent []
    (let [session (ssh/session ip :username user :password password
                               :strict-host-key-checking :no)]
      (when-not (ssh/connected? session)
        (try
          (ssh/connect session 5000) ;; try for 5 seconds only
          (catch Exception _ nil)))
      (when (ssh/connected? session)
        (let [[_ vbox-init-found _]
              (ssh/ssh session
                       :cmd [ "if [ -e /etc/init.d/vbox ] ; then echo \"yes\"; fi"])]
          (if (= "yes\n" vbox-init-found)
            (do (log/debugf "VBox Guest Additions not fully installed yet at %s" ip)
                nil)
            true))))))


(defn- get-ip [machine slot]
  (log/debugf "get-ip: getting IP Address for %s" (:id machine))
  (try (let [ip (vmfest.manager/get-ip machine :slot slot)]
         (if (blank? ip) nil ip))
       (catch RuntimeException e
         (log/debugf "get-ip: Machine %s not started."  (:id machine)))
       (catch Exception e
         (log/debugf "get-ip: Machine %s not accessible." (:id machine)))))

(defn wait-for-installation-finished [machine user password interval timeout]
  ;; todo: this should stop if the machine ceases to exist
  ;; NOTE/CAUTION
  ;; we are making an assumption here that the automated install
  ;; process reboots the machine once it is finished.
  (wait-for
   #(if-let [ip (get-ip machine 1)]
      (fully-installed? ip user password))
   interval
   timeout))

(def defaults
  {:disk-size (* 8 1024) ;; 8GB
   :memory-size (* 2 1024)
   :wait-start 5             ;; 5 seconds
   :wait-boot (* 60 3)       ;; 3 minutes
   :install-poll-interval 5  ;; seconds
   :install-timeout (* 5 60) ;; 5 minutes
   :post-install-wait 30     ;; seconds
   :shut-down-wait 30        ;; seconds
   })

(defn wait-sec [n]
  (Thread/sleep (* 1000 n)))

(defn install-os [server config]
  (let [{:keys [name disk-location disk-size os-iso-location
                vbox-iso-location boot-key-sequence user password
                memory-size wait-start wait-boot install-poll-interval
                install-timeout post-install-wait shut-down-wait]
         :as config}
        (merge defaults config)]
    (log/debugf "Building image for %s based on:\n%s"
               name (with-out-str (pprint config)))
    ;; create the image file where the OS will be installed
    (new-image server {:location disk-location :size disk-size})
    (with-vbox server [_ vbox]
      ;; find if the DVD ISOs are registered, and if not, register them.
      (let [dvd-medium (find-medium vbox os-iso-location)
            vbox-medium (find-medium vbox vbox-iso-location)
            ;; if the dvd image wasn't opened, close it after we're done
            should-close-dvd? (nil? dvd-medium)
            ;; open the medium if it is not already open
            os-medium (or dvd-medium
                          (open-medium vbox os-iso-location :dvd))
            vbox-medium (or vbox-medium
                            (open-medium vbox vbox-iso-location :dvd))
            ;; build the hardware spec for the VM
            hardware-spec (install-machine-spec
                           disk-location
                           os-iso-location
                           vbox-iso-location
                           memory-size)]
        (try
          (let [vm (instance server name {} hardware-spec)]
            (log/infof "%s: Starting VM... " name)
            (start vm)
            ;; wait for the machine to start
            (wait-sec wait-start)
            (send-keyboard vm boot-key-sequence)
            (log/infof "%s: Waiting for installation to finish." name)
            ;; the installation is going to take at least 3 mintues,
            ;; no?, no need to start polling ASAP
            (wait-sec wait-boot)
            ;; let's start testing whether the installation is done
            (wait-for-installation-finished vm user password
                                            (* 1000 install-poll-interval)
                                            (* 1000 install-timeout))
            (log/infof "%s: Installation has finished successfully." name)
            ;; let's give it some time to settle
            (log/infof "%s: Waiting the booting to settle" name)
            (wait-sec post-install-wait)
            (stop vm)
            (log/infof "%s: Waiting for the OS to shut down cleanly" name)
            (wait-sec shut-down-wait)
            (log/infof "%s: Powering the VM down" name)
            (power-down vm)
            (log/infof "%s: Destroying the VM and leaving the image in %s"
                       name disk-location)
            (destroy vm :delete-disks false)
            (log/infof "%s: compacting image at %s " name disk-location)
            (make-disk-immutable server disk-location)
            (log/infof
             "%s: We're done here. You can find your shinny new image at: %s"
             name disk-location)
            disk-location))))))

(comment
  TODO
  - Allow specification of waits in keyboard sequence
  - Allow setting of the bios parameters for a VM -- I need IOApic for multicore
  - Templatize the preseed
  - Automatically set the url for the preseed based
  - Automatically set the proxy if present
  )
(comment
  (use 'visaje.core)
  (use 'vmfest.manager)
  (def my-server (server "http://localhost:18083"))
  (def my-machine
    (install-os my-server
                {:name "debian-test-2"
                 :disk-location "/tmp/debian-test-2.vdi"
                 :disk-size (* 8 1024)
                 :os-iso-location
                 "/Users/tbatchelli/Desktop/ISOS/debian-6.0.2.1-amd64-netinst.iso"
                 :vbox-iso-location
                 "/Users/tbatchelli/Desktop/ISOS/VBoxGuestAdditions.iso"
                 :boot-key-sequence
                  [:esc 500
                   "auto url=http://10.0.2.2/~tbatchelli/deb-preseed.cfg netcfg/choose_interface=eth0"
                   :enter]
                 :user "vmfest"
                 :password "vmfest"})))


