#_"SPDX-License-Identifier: GPL-3.0"

(ns emmy.ratio-test
  (:require #?(:cljs [cljs.reader :refer [read-string]])
            [clojure.test :refer [is deftest testing]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [emmy.generators :as sg]
            [emmy.generic :as g]
            [emmy.generic-test :as gt]
            [emmy.laws :as l]
            [emmy.ratio :as r]
            [emmy.util :as u]
            [emmy.value :as v]
            [same.core :refer [ish?]]))

(deftest ratio-value-implementation
  (testing "v/freeze"
    (is (= '(/ 1 2) (v/freeze #emmy/ratio 1/2)))
    (is (= 2 (v/freeze #emmy/ratio 10/5))
        "Numbers pass through")
    (is (= 2 (v/freeze #emmy/ratio "10/5"))))

  (checking "v/exact? is always true for ratios, v/kind works"
            100
            [r sg/big-ratio]
            (is (v/exact? r))
            (let [k (v/kind r)]
              (is (or (= k r/ratiotype)
                      (= k u/biginttype))
                  "The kind is either ratio, or bigint if the denominator was
                  1."))))

(deftest ratio-laws
  ;; Rational numbers form a field!
  (l/field 100 sg/big-ratio "Ratio"))

(deftest ratio-literal
  (testing "r/parse-ratio can round-trip Ratio instances in clj or cljs. "
    #?(:clj
       (is (= #emmy/ratio "10/3"
              #emmy/ratio "+10/3"
              #emmy/ratio 10/3
              (read-string {:readers {'emmy/ratio r/parse-ratio}}
                           (pr-str #emmy/ratio 10/3)))
           "Ratio parses from numbers and strings.")
       :cljs (is (= `(r/rationalize
                      (u/bigint "10")
                      (u/bigint "3"))
                    (read-string {:readers {'emmy/ratio r/parse-ratio}}
                                 (pr-str #emmy/ratio 10/3)))
                 "Ratio parses from numbers into a code form."))
    (is (= #?(:clj #emmy/ratio "1/999999999999999999999999"
              :cljs `(r/rationalize
                      (u/bigint "1")
                      (u/bigint "999999999999999999999999")))
           (read-string {:readers {'emmy/ratio r/parse-ratio}}
                        (pr-str #emmy/ratio "1/999999999999999999999999")))
        "Parsing #emmy/ratio works with big strings too.")))

(deftest rationalize-test
  (testing "r/rationalize promotes to bigint if evenly divisible"
    (is (not (r/ratio? (r/rationalize 10 2))))
    (is (r/ratio? (r/rationalize 10 3))))

  (checking "r/rationalize round-trips all integrals"
            100
            [x (gen/one-of [sg/any-integral sg/big-ratio])]
            (is (= x (r/rationalize x))))

  (checking "r/rationalize reduces inputs"
            100
            [n sg/any-integral
             d sg/bigint
             :when (and (not (v/zero? d))
                        (not (v/one? d)))]
            (is (= n (g/mul d (r/rationalize n d)))
                "multiplying by denominator recovers numerator")
            (let [r      (r/rationalize n d)
                  factor (g/gcd n d)]
              (when-not (r/ratio? r)
                (is (= (g/abs d)
                       (g/abs factor))
                    "If rationalize doesn't return ratio the denominator must
                    have been the gcd.")

                (is (= (g/abs n)
                       (g/abs (g/mul factor r)))
                    "Recover the original n by multiplying the return value by
                    the factor."))

              (when (r/ratio? r)
                (is (= (g/abs d)
                       (g/abs (g/mul factor (r/denominator r))))
                    "denominator scales down by gcd")
                (is (= (g/abs n)
                       (g/abs (g/mul factor (r/numerator r))))
                    "numerator scales down by gcd")))))

(deftest ratio-generics
  (testing "rational generics"
    (gt/integral-tests r/rationalize)
    (gt/integral-a->b-tests r/rationalize identity)
    (gt/floating-point-tests
     r/rationalize :eq #(= (r/rationalize %1)
                           (r/rationalize %2))))

  (testing "ratio exponent"
    (is (= (-> #emmy/ratio 1/2 (g/expt 3))
           #emmy/ratio 1/8)
        "integral exponents stay exact")

    (is (= (-> #emmy/ratio 1/2 (g/expt (u/long 3)))
           (-> #emmy/ratio 1/2 (g/expt (u/bigint 3)))
           (-> #emmy/ratio 1/2 (g/expt (u/int 3)))
           #emmy/ratio 1/8)
        "different types work")

    (is (ish? (-> #emmy/ratio 1/2 (g/expt 0.5))
              (g/invert (g/sqrt 2)))
        "A non-integral exponent forces to floating point")

    (is (ish? (-> #emmy/ratio 1/2 (g/expt #emmy/ratio 1/2))
              (g/invert (g/sqrt 2)))
        "Same with rational exponents!")

    (is (ish? (g/expt 2 #emmy/ratio 1/2)
              (g/sqrt 2))
        "a rational exponent on an integer will drop precision.")

    (is (ish? (g/expt 0.5 #emmy/ratio 1/2)
              (g/invert (g/sqrt 2)))
        "a rational exponent on a float will drop precision."))

  (testing "GCD between ratio, non-ratio"
    (is (= #emmy/ratio 5/3 (g/gcd 5 #emmy/ratio 10/3)))
    (is (= #emmy/ratio 2/3 (g/gcd #emmy/ratio 10/3 4))))

  (testing "ratio-operations"
    (is (= #emmy/ratio 3/2
           (g/sqrt #emmy/ratio 9/4))
        "Ratios should stay exact if the numerator and denominator are exact.")

    (is (= #emmy/complex "0+1.5i"
           (g/sqrt #emmy/ratio -9/4))
        "sqrt of a negative returns a complex number.")

    (is (= #emmy/ratio 13/40
           (g/add #emmy/ratio 1/5
                  #emmy/ratio 1/8)))

    (is (= #emmy/ratio 1/8
           (g/sub #emmy/ratio 3/8
                  #emmy/ratio 1/4)))

    (is (= #emmy/ratio 5/4 (g/div 5 4)))

    (is (= 2 (g/integer-part #emmy/ratio 9/4)))
    (is (= -2 (g/integer-part #emmy/ratio -9/4)))
    (is (= #emmy/ratio 1/4 (g/fractional-part #emmy/ratio 9/4)))
    (is (= #emmy/ratio 3/4 (g/fractional-part #emmy/ratio -9/4)))

    (is (= 2 (g/floor #emmy/ratio 9/4)))
    (is (= -3 (g/floor #emmy/ratio -9/4)))
    (is (= 3 (g/ceiling #emmy/ratio 9/4)))
    (is (= -2 (g/ceiling #emmy/ratio -9/4)))

    (is (= #emmy/ratio 1/4 (g/modulo #emmy/ratio 9/4 2)))
    (is (= #emmy/ratio 7/4 (g/modulo #emmy/ratio -9/4 2)))
    (is (= #emmy/ratio -7/4 (g/modulo #emmy/ratio 9/4 -2)))
    (is (= #emmy/ratio -1/4 (g/modulo #emmy/ratio -9/4 -2)))
    (is (= #emmy/ratio 1/4 (g/modulo #emmy/ratio 9/4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 5/12 (g/modulo #emmy/ratio -9/4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio -5/12 (g/modulo #emmy/ratio 9/4 #emmy/ratio -2/3)))
    (is (= #emmy/ratio -1/4 (g/modulo #emmy/ratio -9/4 #emmy/ratio -2/3)))
    (is (= 0 (g/modulo -4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 1/3 (g/modulo #emmy/ratio 5 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 1/3 (g/modulo #emmy/ratio -5 #emmy/ratio 2/3)))
    (is (= #emmy/ratio -1/3 (g/modulo #emmy/ratio 5 #emmy/ratio -2/3)))
    (is (= #emmy/ratio -1/3 (g/modulo #emmy/ratio -5 #emmy/ratio -2/3)))

    (is (= #emmy/ratio 1/4 (g/remainder #emmy/ratio 9/4 2)))
    (is (= #emmy/ratio -1/4 (g/remainder #emmy/ratio -9/4 2)))
    (is (= #emmy/ratio 1/4 (g/remainder #emmy/ratio 9/4 -2)))
    (is (= #emmy/ratio -1/4 (g/remainder #emmy/ratio -9/4 -2)))
    (is (= #emmy/ratio 1/4 (g/remainder #emmy/ratio 9/4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio -1/4 (g/remainder #emmy/ratio -9/4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 1/4 (g/remainder #emmy/ratio 9/4 #emmy/ratio -2/3)))
    (is (= #emmy/ratio -1/4 (g/remainder #emmy/ratio -9/4 #emmy/ratio -2/3)))
    (is (= 0 (g/remainder #emmy/ratio -4 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 1/3 (g/remainder #emmy/ratio 5 #emmy/ratio 2/3)))
    (is (= #emmy/ratio -1/3 (g/remainder #emmy/ratio -5 #emmy/ratio 2/3)))
    (is (= #emmy/ratio 1/3 (g/remainder #emmy/ratio 5 #emmy/ratio -2/3)))
    (is (= #emmy/ratio -1/3 (g/remainder #emmy/ratio -5 #emmy/ratio -2/3)))

    (is (= 2 (g/floor #emmy/ratio 9/4)))
    (is (= -3 (g/floor #emmy/ratio -9/4)))
    (is (= 3 (g/ceiling #emmy/ratio 9/4)))
    (is (= -2 (g/ceiling #emmy/ratio -9/4)))

    (is (= 25 (g/exact-divide #emmy/ratio 10/2
                              #emmy/ratio 2/10)))
    (is (= 1 (g/exact-divide #emmy/ratio 2/10
                             #emmy/ratio 2/10)))

    (is (= #emmy/ratio 1/2 (g/div 1 2)))
    (is (= #emmy/ratio 1/4 (reduce g/div [1 2 2])))
    (is (= #emmy/ratio 1/8 (reduce g/div [1 2 2 2])))
    (is (= #emmy/ratio 1/8 (g/invert 8)))))

(deftest with-ratio-literals
  (is (= #emmy/ratio 13/40 (g/+ #emmy/ratio 1/5
                                #emmy/ratio 1/8)))
  (is (= #emmy/ratio 1/8 (g/- #emmy/ratio 3/8
                              #emmy/ratio 1/4)))
  (is (= #emmy/ratio 5/4 (g/divide 5 4)))
  (is (= #emmy/ratio 1/2 (g/divide 1 2)))
  (is (= #emmy/ratio 1/4 (g/divide 1 2 2)))
  (is (= #emmy/ratio 1/8 (g/divide 1 2 2 2))))
