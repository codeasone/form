#!/usr/bin/env just --justfile

format:
  clojure -Sdeps '{:deps {dev.weavejester/cljfmt {:mvn/version "0.11.2"}}}' -M -m cljfmt.main fix

lint:
  clj -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2023.10.20"}}}' -M -m clj-kondo.main --lint .

test:
  clj -M:test

install:
  clj -Ttools install codeasone/form '{:local/root "."}' :as form
