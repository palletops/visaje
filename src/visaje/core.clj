(ns visaje.core
  (:use [vmfest.virtualbox.virtualbox :only [find-medium open-medium]]
        [vmfest.virtualbox.session :only [with-vbox]]
        [vmfest.manager :only [instance start get-machine-attribute
                               send-keyboard stop destroy power-down
                               make-disk-immutable new-image]]
        [clojure.string :only [blank?]]
        [clj-ssh.ssh :only [ssh]]
        [clojure.java.io :only (input-stream)])
  (:require [clojure.tools.logging :as log]))


(defn install-machine-spec [disk-location os-iso-location vbox-iso-location]
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
   :memory-size 2048})

(defn wait-for
  [exit?-fn wait-interval timeout]
  (let [timeout (+ (System/currentTimeMillis) timeout)]
    (loop []
      (let [result (exit?-fn)]
        (if (nil? result)
          (do (Thread/sleep wait-interval)
              (recur))
          result)))))

(defn reached-target-runlevel? [ip user password]
  (let [[_ target-init _]
        (ssh ip :username user
             :password password
             :strict-host-key-checking false
             :cmd [ "/bin/grep initdefault /etc/inittab | cut -d\":\" -f2"])
        target-init (first (clojure.string/split-lines target-init))
        [_ current-init _]
        (ssh ip :username user
             :password password
             :strict-host-key-checking false
             :cmd [ "/sbin/runlevel | cut -d\" \" -f2"])
        current-init (first (clojure.string/split-lines current-init))]
    (log/infof "Target runlevel for %s is %s, and current runlevel is %s"
               ip target-init current-init)
    (when (= target-init current-init)
      current-init)))

(defn- get-ip [machine slot]
  (log/debugf "get-ip: getting IP Address for %s" (:id machine))
  (try (let [ip (vmfest.manager/get-ip machine :slot slot)]
         (if (blank? ip) nil ip))
       (catch RuntimeException e
         (log/debugf "get-ip: Machine %s not started."  (:id machine)))
       (catch Exception e
         (log/debugf "get-ip: Machine %s not accessible." (:id machine)))))

(defn run-commands [machine user password commands]
  (let [ip (get-ip machine 1)]
    (ssh ip :username user :password password
         :strict-host-key-checking false
         :cmd commands)))

(defn wait-for-installation-finished [machine user password interval timeout]
  ;; todo: this should stop if the machine ceases to exist
  ;; NOTE/CAUTION
  ;; we are making an assumption here that the automated install
  ;; process reboots the machine once it is finished.
  (wait-for
   #(if-let [ip (get-ip machine 1)]
      (reached-target-runlevel? ip user password))
   interval
   timeout))

(defn install-os [server name disk-location size-in-mb os-iso-location vbox-iso-location
                  boot-key-sequence user password]
  (new-image server {:location disk-location :size size-in-mb})
  (with-vbox server [_ vbox]
    (let [dvd-medium (find-medium vbox os-iso-location)
          vbox-medium (find-medium vbox vbox-iso-location)
          ;; if the dvd image wasn't opened, close it after we're done
          should-close-dvd? (nil? dvd-medium)
          ;; open the medium if it is not already open
          os-medium (or dvd-medium
                        (open-medium vbox os-iso-location :dvd))
          vbox-medium (or vbox-medium
                          (open-medium vbox vbox-iso-location :dvd))
          hardware-spec (install-machine-spec
                         disk-location
                         os-iso-location
                         vbox-iso-location)]
      (try
        (let [vm (instance server name {} hardware-spec)]
          (log/infof "%s: Starting VM... " name)
          (start vm)
          ;; wait for the machine to start
          (Thread/sleep 5000)
          (send-keyboard vm boot-key-sequence)
          (log/infof "%s: Waiting for installation to finish." name)
          ;; the installation is going to take at least 3 mintues,
          ;; no?, no need to start polling ASAP
          (Thread/sleep (* 3 60 1000))
          ;; let's start testing whether the installation is done
          (wait-for-installation-finished vm user password 5000 300000)
          (log/infof "%s: Installation has finished successfully." name)
          ;; let's give it some time to settle
          (log/infof "%s: Waiting the booting to settle" name)
          (Thread/sleep (* 30 1000))
          (stop vm)
          (log/infof "%s: Waiting for the OS to shut down cleanly" name)
          (Thread/sleep (* 30 1000))
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
          disk-location)))))

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
  (new-image my-server {:location "/tmp/debian-test-2.vdi" :size (* 1024 8)})
  (def my-machine
    (install-os my-server
                "debian-test-2"
                "/tmp/debian-test-2.vdi" (* 8 1024)
                "/Users/tbatchelli/Desktop/ISOS/debian-6.0.2.1-amd64-netinst.iso"
                "/Users/tbatchelli/Desktop/ISOS/VBoxGuestAdditions.iso"
                [:esc 500
                 "auto url=http://10.0.2.2/~tbatchelli/deb-preseed.cfg netcfg/choose_interface=eth0"
                 :enter]
                "vmfest" "vmfest"))

  )


