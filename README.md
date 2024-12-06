# Signaali

- [Project status](#project-status)
- [Usage](#usage)
- [How it works](#how-it-works)
  - [Data phase, stale flagging propagation](#data-phase-stale-flagging-propagation)
  - [Effect phase, pulling the new values & changing the world](#effect-phase-pulling-the-new-values--changing-the-world)
- [State and memo nodes](#state-and-memo-nodes)
- [Effect nodes](#effect-nodes)
- [Execution ordering amongst the effects](#execution-ordering-amongst-the-effects)
- [Disposing the nodes](#disposing-the-nodes)
- [Unit testing](#unit-testing)
- [License](#license)

> Naali: Finnish word for the [artic fox](https://en.wikipedia.org/wiki/Arctic_fox).

This small library contains a generic and flexible CLJC implementation of signals.
It lets you connect data to side effects and lets you run them when their related data
changes.

This type of library can be used for building **reactive systems**.
The author is using it for building a web framework, but it could also be used for many other types
of applications.

With Signaali, you can dynamically create and maintain a directed aclyclic graph
where the nodes are either:
- representing an input data or signal, e.g. `(create-signal value)`,
- representing a derived data, e.g. `(create-derived run-fn)`,
- representing a side effect to be executed, e.g. `(create-effect run-fn)`.

## Project status

[![Clojars Project](https://img.shields.io/clojars/v/fi.metosin/signaali.svg)](https://clojars.org/fi.metosin/signaali)
[![Slack](https://img.shields.io/badge/slack-signaali-orange.svg?logo=slack)](https://clojurians.slack.com/app_redirect?channel=signaali)
[![cljdoc badge](https://cljdoc.org/badge/fi.metosin/signaali)](https://cljdoc.org/d/fi.metosin/signaali)

Signaali is currently [experimental](https://github.com/topics/metosin-experimental).

## Usage

```clojure
(require '[signaali.reactive :as sr])


;; Data and derived data

(def name-of-something (sr/create-signal "Sig the artic fox"))
@name-of-something ;; => "Sig the artic fox"

(def greeting-message (sr/create-derived (fn [] (str "Hello, " @name-of-something "!"))))
@greeting-message ;; => "Hello, Sig the artic fox!"

(reset! name-of-something "Sig naali")
@greeting-message ;; => "Hello, Sig naali!"


;; Effects

(def my-side-effect (sr/create-effect (fn [] (prn @greeting-message))))
;; You can run the effect by hand:
@my-side-effect  ;; "Hello, Sig naali!" is printed
;; alternatively, you can enlist it as a stale effectful node for it
;; to be run later, on the next call of `sr/re-run-stale-effectful-nodes`
#_(sr/enlist-stale-effectful-node my-side-effect)


(reset! name-of-something "Alice")
;; Nothing is printed

(reset! name-of-something "Bob")
;; Nothing is printed

(sr/re-run-stale-effectful-nodes)
;; "Hello, Bob!" is printed

(sr/re-run-stale-effectful-nodes)
;; Nothing is printed


;; Clean up

(sr/-dispose my-side-effect)

(reset! name-of-something "Coco")

(sr/re-run-stale-effectful-nodes)
;; Nothing is printed
```

## How it works

The evaluation of the derived computations and the effects is done lazily, ensuring that each derived
computation and effect that needs to be executed will be executed only once.

This is achieved by having 2 distinct phases:
- When you modify the input data, the *"data phase"*
- When you want the affected effects to re-run, the *"effect phase"*

### Data phase, stale flagging propagation

A signal can be modified similarly to a `clojure.core/atom` via `reset!` or `swap!`.
When it happens, its subscribers are notified of a change.

Each node has a `status` which can be either `:up-to-date`, `:stale` for sure, or `:maybe-stale`.
When notified, if his status was `:up-to-date`, it is changed to either `:stale` or `:maybe-stale`.
When a node becomes stale, it notifies its subscribers
... and so on recursively, until there are no subscribers left to notify.

The stale effect nodes are added to a set to remember them in the next phase.

### Effect phase, pulling the new values & changing the world

When a node is deref'ed (via `clojure.core/deref`, or via its shortcut character `@`),
it always returns its up-to-date value.
- Signal nodes will return their value directly.
- Derived computation nodes and effect nodes will re-run if they are stale,
  their status will be marked as `:up-to-date`,
  the value returned from their run function will be stored,
  then they will return it.

During the effect phase, you typically will deref the effects which were marked stale.
As a user of the library, you decide when to do it:
after every single change or after a batch of changes, depending on your use-case.

## State and memo nodes

There are 2 other nodes:
- `(create-state value)` is the same as a signal node but will only propagate a change when
  updated with value different from the previous one.
- `(create-memo run-fn)` is the same as a derived node but will only propagate a change when
  the value returned by its run function is different from before.

State and memo nodes are by default using the function `sr/not-identical?` when
filtering the propagation of a change, but this can be overridden using an option.

For example:
```clojure
(create-state value {:propagation-filter-fn not=})
```

## Effect nodes

You can register a clean-up callback on each type of node.
It is called exactly once before each re-run of the effect, and also when the node is disposed.

For example:
```clojure
(def book-name (sr/create-state "Alice in wonderland"))

(def improve-mood (sr/create-effect (fn []
                                      (let [book (search-book @book-name)]
                                        (borrow-book! book)
                                        (sr/on-clean-up (fn [] (return-book! book)))
                                        
                                        (read-book! book)
                                        
                                        ;; An effect can return a value
                                        {:mood :happy
                                         :book-name @book-name}))))
                                       
(def mood-printer (sr/create-effect (fn []
                                      ;; Prints the mood after reading a book
                                      (prn @improve-mood))))
```

## Execution ordering amongst the effects

We can ensure an execution between effects if they need to be re-run within the same
call of `sr/re-run-stale-effectful-nodes` via `(sr/-run-after second-effect first-effect)`.

```clojure
(require '[signaali.reactive :as sr])

(def data1 (sr/create-signal :data1))
(def data2 (sr/create-signal :data2))
(def effect1 (sr/create-effect (fn [] (prn :effect1 @data1))))
(def effect2 (sr/create-effect (fn [] (prn :effect2 @data2))))
(sr/enlist-stale-effectful-node effect1)
(sr/enlist-stale-effectful-node effect2)

(sr/re-run-stale-effectful-nodes)
;; Lines printed in arbitrary order:
;; :effect2 :data2
;; :effect1 :data1

(sr/-run-after effect2 effect1)
(reset! data1 :data1)
(reset! data2 :data2)
(sr/re-run-stale-effectful-nodes)
;; Lines printed in deterministic order:
;; :effect1 :data1
;; :effect2 :data2

;; Running effect1 doesn't force effect2 to run
(reset! data1 :data1)
(sr/re-run-stale-effectful-nodes)
;; :effect1 :data1

;; and vice-versa
(reset! data2 :data2)
(sr/re-run-stale-effectful-nodes)
;; :effect2 :data2
```

## Disposing the nodes

Once a node is no longer used, you can dispose it.

Example:
```clojure
(sr/-dispose my-effect)
```

Disposing a node:
- run its `on-clean-up` callback if any is registered,
- unsubscribes it from all its sources (its dependencies),
- unlists it from the `sr/stale-effectful-nodes` set,
- unregisters it from the node priority data structure.

By default, the nodes will be disposed once their subscriber count reaches zero.
If needed, this behavior can be avoided by using the `:dispose-on-zero-subscribers` option.

For example:
```clojure
(sr/create-derived (fn [] ,,,)
                   {:dispose-on-zero-subscribers false})
```

## Unit testing

The tests run in both Clojure & Clojurescript.

```bash
npm install
./bin/kaocha
```

## License

This project is distributed under the [Eclipse Public License v2.0](LICENSE).

Copyright (c) Vincent Cantin and contributors.