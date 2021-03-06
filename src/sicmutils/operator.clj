;
; Copyright (C) 2016 Colin Smith.
; This work is based on the Scmutils system of MIT/GNU Scheme.
;
; This is free software;  you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation; either version 3 of the License, or (at
; your option) any later version.
;
; This software is distributed in the hope that it will be useful, but
; WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
; General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this code; if not, see <http://www.gnu.org/licenses/>.
;

(ns sicmutils.operator
  (:require [sicmutils
             [value :as v]
             [expression :as x]
             [series :as series]
             [generic :as g]])
  (:import (clojure.lang IFn)
           (sicmutils.series Series)))

(defrecord Operator [o arity name]
  v/Value
  (freeze [_] name)
  (kind [_] ::operator)
  (nullity? [_] false)
  (unity? [_] false)
  IFn
  (invoke [_ f] (o f))
  (invoke [_ f g] (o f g))
  (applyTo [_ fns] (apply o fns)))

(defn make-operator
  [o name]
  (Operator. o [:exactly 1] name))

(defn operator?
  [x]
  (instance? Operator x))

(def identity-operator (Operator. identity [:exactly 1] 1))

(defn ^:private number->operator
  [n]
  (Operator. #(g/* n %) [:at-least 0] n))

(defn ^:private o-o
  "Subtract one operator from another. Produces an operator which
  computes the difference of applying the supplied operators."
  [o p]
  (Operator. #(g/- (o %) (p %))
             (v/joint-arity [(:arity o) (:arity p)])
             `(~'- ~o ~p)))

(defn ^:private o+o
  "Add two operators. Produces an operator which adds the result of
  applying the given operators."
  [o p]

  (Operator. #(g/+ (o %) (p %))
             (v/joint-arity [(v/arity o) (v/arity p)])
             `(~'+ ~o ~p)))

;; multiplication of operators is treated like composition.
(defn ^:private o*o
  "Multiplication of operators is defined as their composition"
  [o p]
  (Operator. (with-meta (comp o p) {:arity (:arity p)})
             (:arity p)
             `(~'* ~o ~p)))

(defn ^:private o*f
  "Multiply an operator by a non-operator on the right. The
  non-operator acts on its argument by multiplication."
  [o f]
  (Operator. (fn [& gs]
               (apply o (map (fn [g] (g/* f g)) gs)))
             (:arity o)
             `(~'* ~o ~f)))

(defn ^:private f*o
  "Multiply an operator by a non-operator on the left. The
  non-operator acts on its argument by multiplication."
  [f o]
  (Operator. (fn [& gs]
               (g/* f (apply o gs)))
             (:arity o)
             `(~'* ~f ~o)))

;; Do we need to promote the second arg type (Number)
;; to ::x/numerical-expression?? -- check this ***AG***
(defmethod g/expt
  [::operator Number]
  [o n]
  {:pre [(integer? n)
         (not (neg? n))]}
  (loop [e identity-operator
         n n]
    (if (= n 0) e (recur (o*o e o) (dec n)))))

;; e to an operator g means forming the power series
;; I + g + 1/2 g^2 + ... + 1/n! g^n
;; where (as elsewhere) exponentiating the operator means n-fold composition
(defmethod g/exp
  [::operator]
  [g]
  (letfn [(step [n n! g**n]
            (lazy-seq (cons (g/divide g**n n!)
                            (step (inc n) (* n! (inc n)) (o*o g g**n)))))]
    (Operator. (fn [f]
                 (partial series/value (Series.
                                        [:exactly 0]
                                        (map #(% f) (step 0 1 identity-operator)))))
               [:exactly 1]
               `(~'exp ~g))))

(defmethod g/add [::operator ::operator] [o p] (o+o o p))
;; In additive operation the value 1 is considered as the identity operator
(defmethod g/add [::operator ::x/numerical-expression]
  [o n]
  (o+o o (number->operator n)))
(defmethod g/add [::x/numerical-expression ::operator]
  [n o]
  (o+o (number->operator n) o))
(defmethod g/add
  [::operator :sicmutils.function/function]
  [o f]
  (o+o o (number->operator f)))
(defmethod g/add
  [:sicmutils.function/function ::operator]
  [f o]
  (o+o (number->operator f) o))

(defmethod g/sub [::operator ::operator] [o p] (o-o o p))
(defmethod g/sub
  [::operator ::x/numerical-expression]
  [o n]
  (o-o o (number->operator n)))
(defmethod g/sub
  [::x/numerical-expression ::operator]
  [n o]
  (o-o (number->operator n) o))
(defmethod g/sub
  [::operator :sicmutils.function/function]
  [o f]
  (o-o o (number->operator f)))
(defmethod g/sub
  [:sicmutils.function/function ::operator]
  [f o]
  (o-o (number->operator f) o))

;; Multiplication of operators is defined as their application (see o*o, above)
(defmethod g/mul [::operator ::operator] [o p] (o*o o p))
(defmethod g/mul [::operator :sicmutils.function/function] [o f] (o*f o f))
(defmethod g/mul [:sicmutils.function/function ::operator] [f o] (f*o f o))
;; When multiplied with operators, a number is treated as an operator
;; that multiplies its input by the number.
(defmethod g/mul [::operator ::x/numerical-expression] [o n] (o*f o n))
(defmethod g/mul [::x/numerical-expression ::operator] [n o] (f*o n o))
(defmethod g/div [::operator ::x/numerical-expression] [o n] (o*f o (g/invert n)))

(defmethod g/square [::operator] [o] (o*o o o))

(defmethod g/simplify [::operator] [o] (:name o))

(defmethod g/transpose
  [::operator]
  [o]
  (Operator. (fn [f] #(g/transpose (apply (o f) %&))) 1 'transpose))

(defmethod g/cross-product
  [::operator ::operator]
  [o p]
  (fn [f]
    #(g/cross-product (apply (o f) %&) (apply (p f) %&))))
