# Signaali

- [Project status](#project-status)
- [Intro](#intro)
- [Rationale](#rationale)
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

> Naali: Finnish word for the [arctic fox](https://en.wikipedia.org/wiki/Arctic_fox).

> Sig: The name of an arctic fox.

> Signaali: Finnish word for signal.

## Project status

[![Clojars Project](https://img.shields.io/clojars/v/fi.metosin/signaali.svg)](https://clojars.org/fi.metosin/signaali)
[![Slack](https://img.shields.io/badge/slack-signaali-orange.svg?logo=slack)](https://clojurians.slack.com/app_redirect?channel=signaali)
[![cljdoc badge](https://cljdoc.org/badge/fi.metosin/signaali)](https://cljdoc.org/d/fi.metosin/signaali)

Signaali is currently [experimental](https://github.com/topics/metosin-experimental).
It works and currently has no known bugs (if you find some, please file an issue),
but we might change its API or namespaces to make it reach maturity.

## Intro

This library contains a CLJC implementation of signals, which are used for building **reactive systems**.
The author is using it for building a web framework, but it could also be used for many other types of applications.
You can read more about signals [in this article](https://dev.to/milomg/super-charging-fine-grained-reactive-performance-47ph).

With Signaali, you can dynamically create and maintain a directed acyclic graph
where the nodes are either:
- representing an input data or signal, e.g. `(create-signal value)`,
- representing a derived data, e.g. `(create-derived run-fn)`,
- representing a side effect to be executed, e.g. `(create-effect run-fn)`.

## Rationale

The code base was originally developed for an experimental front-end rendering library which needed a reactive system:
- with strictly no glitches,
- simple to reason about,
- with a simple and small codebase.

A few similar libraries existed already, but none satisfied the above criteria, so this library was created.

The code doesn't try to be "the most performant", as in many cases it is performant enough.
Instead, a higher priority was placed on the developer convenience and simplicity.
If performance becomes a real need (e.g. better use of the memory and CPU), this library should be forked and tweaked - maybe some features
won't be needed in your specific use case.

If your use case is not covered by Signaali, let's have a talk on Slack and see if we can help.

## Usage

```clojure
(require '[signaali.reactive :as sr])


;; Data and derived data

(def name-of-something (sr/create-signal "Sig the arctic fox"))
@name-of-something ;; => "Sig the arctic fox"

(def greeting-message (sr/create-derived (fn [] (str "Hello, " @name-of-something "!"))))
@greeting-message ;; => "Hello, Sig the arctic fox!"

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

(sr/dispose my-side-effect)

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
When it happens, its signal watchers are notified of a change.

Each node has a `status` which can be either `:up-to-date`, `:stale` for sure, or `:maybe-stale`.
When notified, if his status was `:up-to-date`, it is changed to either `:stale` or `:maybe-stale`.
When a node becomes stale, it notifies its signal watchers
... and so on recursively, until there are no signal watchers left to notify.

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
(def book-name
  (sr/create-state "Alice in wonderland"))

(def book-reader
  (sr/create-effect
   (fn []
     (let [book-name @book-name]
       (prn (str "borrow " book-name " from library"))
       (sr/on-clean-up (fn [] (prn (str "return " book-name " to library"))))

       (prn (str "read " book-name))

       ;; An effect can return a value
       {:page-count 100}))))

(sr/enlist-stale-effectful-node book-reader)

(def total-page-count
  (sr/create-state 0))

(def page-count-aggregator
  (sr/create-effect
   (fn []
     (swap! total-page-count + (:page-count @book-reader)))))

(sr/enlist-stale-effectful-node page-count-aggregator)

(sr/re-run-stale-effectful-nodes)
;; "borrow Alice in wonderland from library" is printed
;; "read Alice in wonderland" is printed

@total-page-count ; => 100

(reset! book-name "Pepper & Carrot")

(sr/re-run-stale-effectful-nodes)
;; "return Alice in wonderland to library" is printed
;; "borrow Pepper & Carrot from library" is printed
;; "read Pepper & Carrot" is printed
@total-page-count ; => 200
```

## Execution ordering amongst the effects

We can ensure an execution order between effects if they need to be re-run within the same
call of `sr/re-run-stale-effectful-nodes` via `(sr/run-after second-effect first-effect)`.

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

(sr/run-after effect2 effect1)

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
(sr/dispose my-effect)
```

Disposing a node:
- run its `on-clean-up` callback if any is registered,
- unsubscribes it from all its sources (its dependencies),
- unlists it from the `sr/stale-effectful-nodes` set,
- unregisters it from the node priority data structure.

By default, the nodes will be disposed once their signal watcher count reaches zero.
If needed, this behavior can be avoided by using the `:dispose-on-zero-signal-watchers` option.

For example:
```clojure
(sr/create-derived 
 (fn [] ,,,)
 {:dispose-on-zero-signal-watchers false})
```

## Unit testing

The tests run in both Clojure & Clojurescript.

```bash
npm install
./bin/kaocha
```

## Similar Clojure libraries

- [Reagent Atom](https://github.com/reagent-project/reagent/blob/master/src/reagent/ratom.cljs), CLJS only.
- [Signals](https://github.com/kunstmusik/signals), CLJ only.
- [Flex](https://github.com/lilactown/flex), CLJC.
- [Matrix](https://github.com/kennytilton/matrix), CLJC.

Quite different but on the same topic:
- [Missionary](https://github.com/leonoel/missionary)

Please make a PR if you think that a library is missing from the list.

## License

This project is distributed under the [Eclipse Public License v2.0](LICENSE).

Copyright (c) Vincent Cantin and contributors.
