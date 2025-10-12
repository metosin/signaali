# Signaali Reactive Programming Library

## Overview

Signaali is a ClojureScript/Clojure reactive programming library that implements signals for building reactive systems.

## Core Concepts

### Reactive Nodes

Signaali creates a directed acyclic graph where nodes represent:
- **Signals/input data**: `create-signal`
- **Derived computations**: `create-derived`
- **Side effects**: `create-effect`

### Two-Phase Execution Model

#### Phase 1: Data Phase (Stale Flagging)

When you modify input data, Signaali:
1. Marks dependent nodes as `:stale` or `:maybe-stale`
2. Propagates stale flags through the dependency graph
3. Collects stale effects for later execution
4. **No computations run yet** - just bookkeeping

#### Phase 2: Effect Phase (Lazy Pulling)

When you trigger effect execution:
1. Effects pull fresh values by deref'ing their dependencies
2. Stale derived values are recomputed on-demand
3. Each computation runs exactly once per update cycle
4. Side effects occur

## Basic API

### Reactive Node Creation

```clojure
(require '[signaali.reactive :as sr])

;; Basic signal - can be updated
(def name-signal (sr/create-signal "Alice"))
@name-signal ;; => "Alice"

;; State - only propagates when value actually changes  
(def counter (sr/create-state 0))

;; Derived computation - runs when dependencies change
(def greeting (sr/create-derived 
  (fn [] 
    (str "Hello, " @name-signal "!"))))

;; Memo - like derived but only propagates when result changes
(def expensive-calc (sr/create-memo
  (fn []
    (expensive-computation @some-input))))

;; Effect - for side effects like DOM updates
(def dom-updater (sr/create-effect
  (fn []
    (update-dom-element @greeting))))
```

### Updating Values

```clojure
;; Update signals like atoms
(reset! name-signal "Bob")
(swap! counter inc)

;; This only marks nodes as stale - effects don't run yet
```

### Running Effects

```clojure
;; Manual effect execution
@dom-updater  ; Runs the effect immediately

;; Batch execution (preferred)
(sr/enlist-stale-effectful-node dom-updater)
(sr/re-run-stale-effectful-nodes)  ; Runs all enlisted stale effects
```

## Dependency Tracking

Signaali automatically tracks dependencies at runtime by monitoring `deref` operations:

```clojure
(def first-name (sr/create-signal "Alice"))
(def last-name (sr/create-signal "Smith"))

;; This derived computation automatically depends on both signals
(def full-name (sr/create-derived
  (fn []
    (str @first-name " " @last-name))))  ; Signaali tracks both derefs

;; Changing either signal will mark full-name as stale
(reset! first-name "Bob")  ; full-name becomes stale
@full-name  ; => "Bob Smith" (recomputes lazily)
```

## Effect Management

### Basic Effects

```clojure
(def my-effect (sr/create-effect
  (fn []
    (println "Current value:" @some-signal)
    ;; Effects can return values
    {:status :updated})))

;; First-time enlistment (only needed for effects that haven't run yet)
(sr/enlist-stale-effectful-node my-effect)
(sr/re-run-stale-effectful-nodes)

;; Shortcut: @my-effect does the same as the above two lines
@my-effect

;; After first run, effects auto-enlist when dependencies change
(reset! some-signal "new-value")
(sr/re-run-stale-effectful-nodes)  ; my-effect runs automatically

;; Alternative: provide dependencies upfront to avoid first-time enlistment
;; and prevent running the effect before any data changes
(def my-effect-with-deps (sr/create-effect
  (fn []
    (println "Current value:" @some-signal))
  {:deps [some-signal]}))

;; No manual enlistment needed - effect subscribes immediately
(reset! some-signal "another-value")
(sr/re-run-stale-effectful-nodes)
```

### Cleanup Callbacks

```clojure
(def timer-effect (sr/create-effect
 (fn []
   (let [interval-id (js/setInterval #(println "tick") @interval-delay-signal)]
     ;; Cleanup runs before re-run and when disposed
     (sr/on-clean-up #(js/clearInterval interval-id))))))
```

### Effect Ordering

```clojure
(def effect1 (sr/create-effect (fn [] (println "First"))))
(def effect2 (sr/create-effect (fn [] (println "Second"))))

;; Ensure effect1 runs before effect2
(sr/run-after effect2 effect1)

(sr/enlist-stale-effectful-node effect1)
(sr/enlist-stale-effectful-node effect2)
(sr/re-run-stale-effectful-nodes)
;; Prints: "First" then "Second"
```

## Node Lifecycle

### Disposal

```clojure
;; Clean up when no longer needed
(sr/dispose my-effect)
;; This:
;; - Runs cleanup callbacks
;; - Unsubscribes from dependencies  
;; - Removes from stale effects set
```

### Auto-disposal

```clojure
;; Nodes auto-dispose when they have no watchers (default)
(sr/create-derived 
  (fn [] @some-signal)
  {:dispose-on-zero-signal-watchers true})  ; default

;; Prevent auto-disposal if needed
(sr/create-derived 
  (fn [] @some-signal)
  {:dispose-on-zero-signal-watchers false})
```

## Advanced Usage Patterns

### Conditional Dependencies

```clojure
(def mode (sr/create-signal :development))
(def dev-config (sr/create-signal {...}))
(def prod-config (sr/create-signal {...}))

(def active-config (sr/create-derived
  (fn []
    (case @mode
      :development @dev-config    ; Only depends on dev-config in dev mode
      :production @prod-config))))  ; Only depends on prod-config in prod mode
```

### State with Custom Change Detection

```clojure
;; Only propagate when values are not equal (instead of not identical)
(def my-state (sr/create-state initial-value 
  {:propagation-filter-fn not=}))

;; Custom comparison for complex data
(def complex-state (sr/create-state initial-data
  {:propagation-filter-fn (fn [old new] 
                           (not (deep-equal? old new)))}))
```

### Batched Updates

```clojure
;; Make multiple changes
(reset! signal1 "new-value-1")
(reset! signal2 "new-value-2") 
(swap! signal3 inc)

;; All effects run together
(sr/re-run-stale-effectful-nodes)  ; Single batch update
```

## Debugging Tips

### Tracing Dependencies

```clojure
;; Add logging to see what triggers recomputation
(def debug-derived (sr/create-derived
  (fn []
    (println "Recomputing with:" @source-signal)
    @source-signal)))
```

### Effect Execution Tracking

```clojure
(def tracked-effect (sr/create-effect
  (fn []
    (println "Effect running at:" (js/Date.))
    (do-side-effect @some-signal))))
```

## Best Practices

1. **Use appropriate node types**: Signals for input, derived for computations, effects for side effects

2. **Batch updates**: Use `re-run-stale-effectful-nodes` instead of manual deref'ing effects

3. **Clean up resources**: Always use `on-clean-up` for resources that need disposal

4. **Keep effects focused**: One effect per concern (DOM update, network request, etc.)

5. **Use memos for expensive computations**: Especially when the result might not change

6. **Dispose unused nodes**: Call `sr/dispose` or rely on auto-disposal

7. **Order effects when needed**: Use `run-after` for dependent effects

This reactive model provides the foundation for efficient, glitch-free UI updates in web frameworks like Vrac, where DOM changes are triggered by data changes through the signal system.
