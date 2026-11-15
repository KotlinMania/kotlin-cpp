package io.github.kotlinmania.kotlincpp.kcpp

internal fun emitSharedHelperPreamble(
    includes: CppIncludeCollector,
    out: StringBuilder,
) {
    if (includes.rootForwardDeclarations.isNotEmpty()) {
        out.append('\n')
        for (fwd in includes.rootForwardDeclarations) {
            out.append(fwd).append('\n')
        }
    }
    if (includes.needsSequenceAlias) {
        out.append(
            """

            #ifndef KXS_SEQUENCE_DEFINED
            #define KXS_SEQUENCE_DEFINED
            template <typename E>
            struct KxsSequence : public std::vector<E> {
                using std::vector<E>::vector;
            };
            struct KxsEmptySequence {
                template <typename E>
                operator KxsSequence<E>() const { return {}; }
            };
            inline KxsEmptySequence empty_sequence() { return {}; }
            template <typename E>
            inline KxsSequence<E> empty_sequence() { return {}; }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsIntRangeDef) {
        out.append(
            """

            #ifndef KXS_INT_RANGE_DEFINED
            #define KXS_INT_RANGE_DEFINED
            template <typename T>
            struct KxsIntRange {
                T start_{};
                T end_inclusive_{};
                struct iterator {
                    T cur_;
                    constexpr T operator*() const noexcept { return cur_; }
                    constexpr iterator& operator++() noexcept { ++cur_; return *this; }
                    constexpr bool operator!=(const iterator& other) const noexcept { return cur_ != other.cur_; }
                };
                constexpr iterator begin() const noexcept { return iterator{start_}; }
                constexpr iterator end() const noexcept {
                    return start_ <= end_inclusive_ ? iterator{static_cast<T>(end_inclusive_ + 1)} : iterator{start_};
                }
                constexpr bool contains(T value) const noexcept { return start_ <= value && value <= end_inclusive_; }
                constexpr bool is_empty() const noexcept { return start_ > end_inclusive_; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsTrimIndentDef) {
        out.append(
            """

            #ifndef KXS_TRIM_INDENT_DEFINED
            #define KXS_TRIM_INDENT_DEFINED
            inline std::string kxs_trim_indent(const std::string& s) {
                std::vector<std::string> lines;
                std::istringstream iss(s);
                std::string line;
                while (std::getline(iss, line)) {
                    lines.push_back(line);
                }
                while (!lines.empty() && lines.front().find_first_not_of(" \t\r") == std::string::npos) {
                    lines.erase(lines.begin());
                }
                while (!lines.empty() && lines.back().find_first_not_of(" \t\r") == std::string::npos) {
                    lines.pop_back();
                }
                std::size_t min_indent = std::string::npos;
                for (const auto& l : lines) {
                    const auto first_non_ws = l.find_first_not_of(" \t");
                    if (first_non_ws != std::string::npos) {
                        min_indent = std::min(min_indent, first_non_ws);
                    }
                }
                if (min_indent == std::string::npos) min_indent = 0;
                std::string result;
                for (std::size_t i = 0; i < lines.size(); ++i) {
                    if (i > 0) result += '\n';
                    if (lines[i].find_first_not_of(" \t\r") != std::string::npos && lines[i].size() > min_indent) {
                        result += lines[i].substr(min_indent);
                    } else if (lines[i].find_first_not_of(" \t\r") != std::string::npos) {
                        result += lines[i];
                    }
                }
                return result;
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsContextOpsDef && includes.selfHeaderPath != "kotlinx/coroutines/CoroutineContext.hpp") {
        out.append(
            """

            #ifndef KXS_CONTEXT_OPS_DEFINED
            #define KXS_CONTEXT_OPS_DEFINED
            namespace kotlin::coroutines {
            template <typename KxsL, typename KxsR>
                requires (std::derived_from<std::remove_cv_t<KxsL>, CoroutineContext> &&
                          std::derived_from<std::remove_cv_t<KxsR>, CoroutineContext>)
            inline std::shared_ptr<CoroutineContext> operator+(const std::shared_ptr<KxsL>& lhs, const std::shared_ptr<KxsR>& rhs) {
                if (!lhs) return std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(rhs));
                if (!rhs) return std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(lhs));
                return lhs->operator+(std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(rhs)));
            }
            template <typename KxsElem>
            inline std::shared_ptr<KxsElem> kxs_cast_ctx_elem(
                const CoroutineContext::KeyTyped<KxsElem>*,
                std::shared_ptr<CoroutineContext::Element> elem
            ) {
                return std::dynamic_pointer_cast<KxsElem>(std::move(elem));
            }
            inline std::shared_ptr<CoroutineContext::Element> kxs_cast_ctx_elem(
                const void*,
                std::shared_ptr<CoroutineContext::Element> elem
            ) {
                return elem;
            }
            template <typename KxsCtx, typename KxsKey>
            inline auto kxs_ctx_get(KxsCtx&& ctx, KxsKey&& key) {
                using CtxD = std::remove_cvref_t<KxsCtx>;
                using KeyD = std::remove_cvref_t<KxsKey>;
                const auto* key_raw_ptr = [&]() {
                    if constexpr (std::is_pointer_v<KeyD>) return key;
                    else return std::addressof(key);
                }();
                auto* base_key = const_cast<CoroutineContext::Key*>(static_cast<const CoroutineContext::Key*>(key_raw_ptr));
                std::shared_ptr<CoroutineContext::Element> elem;
                if constexpr (requires { ctx.get(); typename CtxD::element_type; }) {
                    if (ctx) elem = ctx->get(base_key);
                } else if constexpr (std::is_pointer_v<CtxD>) {
                    if (ctx) elem = ctx->get(base_key);
                } else {
                    elem = ctx.get(base_key);
                }
                return kxs_cast_ctx_elem(key_raw_ptr, std::move(elem));
            }
            } // namespace kotlin::coroutines
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSynchronizedObjectDef) {
        out.append(
            """

            #ifndef KXS_SYNCHRONIZED_OBJECT_DEFINED
            #define KXS_SYNCHRONIZED_OBJECT_DEFINED
            struct KxsSynchronizedObject {
                mutable std::recursive_mutex kxs_sync_mutex_{};
                KxsSynchronizedObject() = default;
                KxsSynchronizedObject(const KxsSynchronizedObject&) noexcept {}
                KxsSynchronizedObject& operator=(const KxsSynchronizedObject&) noexcept { return *this; }
                virtual ~KxsSynchronizedObject() = default;
                void lock() const { kxs_sync_mutex_.lock(); }
                void unlock() const { kxs_sync_mutex_.unlock(); }
                bool try_lock() const { return kxs_sync_mutex_.try_lock(); }
                template <typename KxsFn>
                decltype(auto) with_lock(KxsFn&& fn) const {
                    std::lock_guard<std::recursive_mutex> lk(kxs_sync_mutex_);
                    return std::forward<KxsFn>(fn)();
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsAutoCloseableDef) {
        out.append(
            """

            #ifndef KXS_AUTOCLOSEABLE_DEFINED
            #define KXS_AUTOCLOSEABLE_DEFINED
            class AutoCloseable {
            public:
                virtual ~AutoCloseable() = default;
                virtual void close() = 0;
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsArrayDequeDef) {
        out.append(
            """

            #ifndef KXS_ARRAY_DEQUE_DEFINED
            #define KXS_ARRAY_DEQUE_DEFINED
            template <typename T>
            class KxsArrayDeque {
            private:
                mutable std::deque<T> dq_{};
            public:
                KxsArrayDeque() = default;
                explicit KxsArrayDeque(std::int32_t) {}
                void add_last(T element) const { dq_.push_back(std::move(element)); }
                void add_first(T element) const { dq_.push_front(std::move(element)); }
                bool add(T element) const { dq_.push_back(std::move(element)); return true; }
                auto remove_first_or_null() const {
                    if constexpr (std::is_pointer_v<T> || requires(T v) { v == nullptr; }) {
                        if (dq_.empty()) return T{};
                        T v = std::move(dq_.front());
                        dq_.pop_front();
                        return v;
                    } else {
                        if (dq_.empty()) return std::optional<T>{};
                        T v = std::move(dq_.front());
                        dq_.pop_front();
                        return std::optional<T>{std::move(v)};
                    }
                }
                auto remove_last_or_null() const {
                    if constexpr (std::is_pointer_v<T> || requires(T v) { v == nullptr; }) {
                        if (dq_.empty()) return T{};
                        T v = std::move(dq_.back());
                        dq_.pop_back();
                        return v;
                    } else {
                        if (dq_.empty()) return std::optional<T>{};
                        T v = std::move(dq_.back());
                        dq_.pop_back();
                        return std::optional<T>{std::move(v)};
                    }
                }
                T remove_first() const {
                    T v = std::move(dq_.front());
                    dq_.pop_front();
                    return v;
                }
                bool is_empty() const noexcept { return dq_.empty(); }
                bool empty() const noexcept { return dq_.empty(); }
                std::int32_t size() const noexcept { return static_cast<std::int32_t>(dq_.size()); }
                void clear() const noexcept { dq_.clear(); }
                auto begin() noexcept { return dq_.begin(); }
                auto end() noexcept { return dq_.end(); }
                auto begin() const noexcept { return dq_.begin(); }
                auto end() const noexcept { return dq_.end(); }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsThreadLocalDef) {
        out.append(
            """

            #ifndef KXS_THREAD_LOCAL_DEFINED
            #define KXS_THREAD_LOCAL_DEFINED
            template <typename T>
            class KxsThreadLocal {
            private:
                static std::unordered_map<const KxsThreadLocal<T>*, T>& map() {
                    thread_local std::unordered_map<const KxsThreadLocal<T>*, T> tls{};
                    return tls;
                }
            public:
                KxsThreadLocal() = default;
                T get() const {
                    auto& m = map();
                    auto it = m.find(this);
                    return it == m.end() ? T{} : it->second;
                }
                void set(T value) const {
                    map()[this] = std::move(value);
                }
                void remove() const {
                    map().erase(this);
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsAtomicReferenceDef) {
        out.append(
            """

            #ifndef KXS_ATOMIC_REFERENCE_DEFINED
            #define KXS_ATOMIC_REFERENCE_DEFINED
            template <typename V>
            class KxsAtomicReference {
            private:
                mutable std::mutex mtx_{};
                V val_{};
                const std::type_info* eq_type_{nullptr};
                bool (*eq_fn_)(const std::any&, const std::any&){nullptr};

                static V kxs_null_v() {
                    return V{};
                }

                template <typename U>
                static bool kxs_any_typed_eq(const std::any& a, const std::any& b) {
                    const auto* pa = std::any_cast<U>(&a);
                    const auto* pb = std::any_cast<U>(&b);
                    if (!pa || !pb) return false;
                    if constexpr (requires(const U& x, const U& y) { x == y; }) {
                        return *pa == *pb;
                    } else {
                        return pa == pb;
                    }
                }

                static bool kxs_is_any_null(const std::any& a) noexcept {
                    return !a.has_value() || a.type() == typeid(std::nullptr_t);
                }

                static bool kxs_any_pair_eq(
                    const std::any& cur,
                    const std::any& expected,
                    const std::type_info* eq_type,
                    bool (*eq_fn)(const std::any&, const std::any&)
                ) {
                    if (kxs_is_any_null(cur) && kxs_is_any_null(expected)) return true;
                    if (kxs_is_any_null(cur) || kxs_is_any_null(expected)) return false;
                    if (cur.type() != expected.type()) return false;
                    if (eq_fn != nullptr && eq_type != nullptr && *eq_type == cur.type()) {
                        return eq_fn(cur, expected);
                    }
                    if (const auto* p1 = std::any_cast<void*>(&cur)) {
                        if (const auto* p2 = std::any_cast<void*>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<const void*>(&cur)) {
                        if (const auto* p2 = std::any_cast<const void*>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::int32_t>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::int32_t>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::int64_t>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::int64_t>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<bool>(&cur)) {
                        if (const auto* p2 = std::any_cast<bool>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::string>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::string>(&expected)) return *p1 == *p2;
                    }
                    return false;
                }

                template <typename Exp>
                bool kxs_matches_unlocked(const Exp& expected) const {
                    using ExpD = std::remove_cvref_t<Exp>;
                    if constexpr (std::is_same_v<V, std::any>) {
                        if constexpr (std::is_same_v<ExpD, std::nullptr_t>) {
                            return kxs_is_any_null(val_);
                        } else if constexpr (std::is_same_v<ExpD, std::any>) {
                            return kxs_any_pair_eq(val_, expected, eq_type_, eq_fn_);
                        } else {
                            if (kxs_is_any_null(val_)) return false;
                            if (const auto* p = std::any_cast<ExpD>(&val_)) {
                                if constexpr (requires(const ExpD& x, const ExpD& y) { x == y; }) {
                                    return *p == expected;
                                } else {
                                    return false;
                                }
                            }
                            return false;
                        }
                    } else {
                        if constexpr (std::is_same_v<ExpD, std::nullptr_t>) {
                            if constexpr (requires { val_ == nullptr; }) {
                                return val_ == nullptr;
                            } else if constexpr (requires { !val_.has_value(); }) {
                                return !val_.has_value();
                            } else {
                                return false;
                            }
                        } else if constexpr (requires { val_ == expected; }) {
                            return val_ == expected;
                        } else {
                            V exp_v(expected);
                            if constexpr (requires { val_ == exp_v; }) {
                                return val_ == exp_v;
                            } else {
                                return false;
                            }
                        }
                    }
                }

                template <typename Des>
                void kxs_store_unlocked(Des&& desired) {
                    using DesD = std::decay_t<Des>;
                    if constexpr (std::is_same_v<V, std::any>) {
                        if constexpr (std::is_same_v<DesD, std::nullptr_t>) {
                            val_.reset();
                            eq_type_ = nullptr;
                            eq_fn_ = nullptr;
                        } else if constexpr (std::is_same_v<DesD, std::any>) {
                            if (kxs_is_any_null(desired)) {
                                val_.reset();
                                eq_type_ = nullptr;
                                eq_fn_ = nullptr;
                            } else {
                                if (eq_type_ == nullptr || *eq_type_ != desired.type()) {
                                    eq_type_ = nullptr;
                                    eq_fn_ = nullptr;
                                }
                                val_ = std::forward<Des>(desired);
                            }
                        } else {
                            val_ = std::forward<Des>(desired);
                            eq_type_ = &typeid(DesD);
                            eq_fn_ = &kxs_any_typed_eq<DesD>;
                        }
                    } else {
                        if constexpr (std::is_same_v<DesD, std::nullptr_t>) {
                            if constexpr (requires { val_.reset(); }) {
                                val_.reset();
                            } else {
                                val_ = kxs_null_v();
                            }
                        } else if constexpr (std::is_assignable_v<V&, Des>) {
                            val_ = std::forward<Des>(desired);
                        } else if constexpr (
                            requires { val_.emplace(*std::forward<Des>(desired)); } &&
                            requires { desired.has_value(); *desired; }
                        ) {
                            if (desired.has_value()) {
                                val_.emplace(*std::forward<Des>(desired));
                            } else {
                                val_.reset();
                            }
                        } else if constexpr (requires { val_.emplace(std::forward<Des>(desired)); }) {
                            val_.emplace(std::forward<Des>(desired));
                        } else {
                            val_ = static_cast<V>(std::forward<Des>(desired));
                        }
                    }
                }
                template <typename KxsDst, typename KxsSrc>
                static void kxs_assign_val(KxsDst& dst, KxsSrc&& src) {
                    if constexpr (std::is_assignable_v<KxsDst&, KxsSrc>) {
                        dst = std::forward<KxsSrc>(src);
                    } else if constexpr (
                        requires { dst.reset(); dst.emplace(*std::forward<KxsSrc>(src)); src.has_value(); }
                    ) {
                        if (src.has_value()) {
                            dst.emplace(*std::forward<KxsSrc>(src));
                        } else {
                            dst.reset();
                        }
                    } else if constexpr (std::is_constructible_v<KxsDst, KxsSrc>) {
                        std::destroy_at(std::addressof(dst));
                        std::construct_at(std::addressof(dst), std::forward<KxsSrc>(src));
                    }
                }
            public:
                KxsAtomicReference() = default;
                explicit KxsAtomicReference(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) : val_(kxs_null_v()) {}
                explicit KxsAtomicReference(V v) : val_(std::move(v)) {}
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, KxsAtomicReference> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                explicit KxsAtomicReference(T&& v) {
                    kxs_store_unlocked(std::forward<T>(v));
                }
                KxsAtomicReference(const KxsAtomicReference& o) {
                    std::lock_guard<std::mutex> lk(o.mtx_);
                    kxs_assign_val(val_, o.val_);
                    eq_type_ = o.eq_type_;
                    eq_fn_ = o.eq_fn_;
                }
                KxsAtomicReference(KxsAtomicReference&& o) noexcept {
                    std::lock_guard<std::mutex> lk(o.mtx_);
                    kxs_assign_val(val_, std::move(o.val_));
                    eq_type_ = o.eq_type_;
                    eq_fn_ = o.eq_fn_;
                }
                KxsAtomicReference& operator=(const KxsAtomicReference& o) {
                    if (this != &o) {
                        std::scoped_lock lk(mtx_, o.mtx_);
                        kxs_assign_val(val_, o.val_);
                        eq_type_ = o.eq_type_;
                        eq_fn_ = o.eq_fn_;
                    }
                    return *this;
                }
                KxsAtomicReference& operator=(KxsAtomicReference&& o) noexcept {
                    if (this != &o) {
                        std::scoped_lock lk(mtx_, o.mtx_);
                        kxs_assign_val(val_, std::move(o.val_));
                        eq_type_ = o.eq_type_;
                        eq_fn_ = o.eq_fn_;
                    }
                    return *this;
                }
                V get_value() const {
                    std::lock_guard<std::mutex> lk(mtx_);
                    return val_;
                }
                void set_value(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(nullptr);
                }
                void set_value(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(std::move(v));
                }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                void set_value(T&& v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(std::forward<T>(v));
                }
                V get_and_set(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(nullptr);
                    return old;
                }
                V get_and_set(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(std::move(v));
                    return old;
                }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                V get_and_set(T&& v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(std::forward<T>(v));
                    return old;
                }
                bool compare_and_set(const V& expected, V desired) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(std::nullptr_t, V desired) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(nullptr)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(const V& expected, std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(std::nullptr_t, std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(nullptr)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    return false;
                }
                template <typename Exp, typename Des>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::any> && std::same_as<std::remove_cvref_t<Des>, std::any>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::nullptr_t> && std::same_as<std::remove_cvref_t<Des>, std::nullptr_t>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::nullptr_t> && std::same_as<std::remove_cvref_t<Des>, std::any>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::any> && std::same_as<std::remove_cvref_t<Des>, std::nullptr_t>)
                    )
                bool compare_and_set(Exp&& expected, Des&& desired) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::forward<Des>(desired));
                        return true;
                    }
                    return false;
                }
                V load(std::memory_order = std::memory_order_seq_cst) const { return get_value(); }
                void store(std::nullptr_t, std::memory_order = std::memory_order_seq_cst) requires (!std::is_same_v<V, std::nullptr_t>) { set_value(nullptr); }
                void store(V v, std::memory_order = std::memory_order_seq_cst) { set_value(std::move(v)); }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                void store(T&& v, std::memory_order = std::memory_order_seq_cst) { set_value(std::forward<T>(v)); }
                V exchange(std::nullptr_t, std::memory_order = std::memory_order_seq_cst) requires (!std::is_same_v<V, std::nullptr_t>) { return get_and_set(nullptr); }
                V exchange(V v, std::memory_order = std::memory_order_seq_cst) { return get_and_set(std::move(v)); }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                V exchange(T&& v, std::memory_order = std::memory_order_seq_cst) { return get_and_set(std::forward<T>(v)); }
                bool compare_exchange_strong(
                    V& expected,
                    V desired,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    expected = val_;
                    return false;
                }
                bool compare_exchange_strong(
                    V& expected,
                    std::nullptr_t,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    expected = val_;
                    return false;
                }
                template <typename Exp, typename Des>
                    requires (!std::same_as<std::remove_cvref_t<Exp>, V>)
                bool compare_exchange_strong(
                    Exp& expected,
                    Des&& desired,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::forward<Des>(desired));
                        return true;
                    }
                    if constexpr (!std::is_const_v<Exp> && std::is_assignable_v<Exp&, const V&>) {
                        expected = val_;
                    }
                    return false;
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCopyableAtomicDef) {
        out.append(
            """

            #ifndef KXS_COPYABLE_ATOMIC_DEFINED
            #define KXS_COPYABLE_ATOMIC_DEFINED
            template <typename T>
            class KxsCopyableAtomic {
            private:
                std::atomic<T> val_{};
            public:
                KxsCopyableAtomic() = default;
                constexpr KxsCopyableAtomic(T v) noexcept : val_(v) {}
                KxsCopyableAtomic(const KxsCopyableAtomic& o) noexcept : val_(o.val_.load(std::memory_order_seq_cst)) {}
                KxsCopyableAtomic(KxsCopyableAtomic&& o) noexcept : val_(o.val_.load(std::memory_order_seq_cst)) {}
                KxsCopyableAtomic& operator=(const KxsCopyableAtomic& o) noexcept {
                    if (this != &o) val_.store(o.val_.load(std::memory_order_seq_cst), std::memory_order_seq_cst);
                    return *this;
                }
                KxsCopyableAtomic& operator=(KxsCopyableAtomic&& o) noexcept {
                    if (this != &o) val_.store(o.val_.load(std::memory_order_seq_cst), std::memory_order_seq_cst);
                    return *this;
                }
                T operator=(T desired) noexcept {
                    val_.store(desired, std::memory_order_seq_cst);
                    return desired;
                }
                operator std::atomic<T>&() noexcept { return val_; }
                operator const std::atomic<T>&() const noexcept { return val_; }
                T load(std::memory_order order = std::memory_order_seq_cst) const noexcept { return val_.load(order); }
                void store(T desired, std::memory_order order = std::memory_order_seq_cst) noexcept { val_.store(desired, order); }
                T exchange(T desired, std::memory_order order = std::memory_order_seq_cst) noexcept { return val_.exchange(desired, order); }
                bool compare_exchange_strong(
                    T& expected,
                    T desired,
                    std::memory_order success = std::memory_order_seq_cst,
                    std::memory_order failure = std::memory_order_seq_cst
                ) noexcept {
                    return val_.compare_exchange_strong(expected, desired, success, failure);
                }
                bool compare_exchange_weak(
                    T& expected,
                    T desired,
                    std::memory_order success = std::memory_order_seq_cst,
                    std::memory_order failure = std::memory_order_seq_cst
                ) noexcept {
                    return val_.compare_exchange_weak(expected, desired, success, failure);
                }
                T get_value() const noexcept { return load(); }
                void set_value(T v) noexcept { store(v); }
                T get_and_set(T v) noexcept { return exchange(v); }
                bool compare_and_set(T expected, T desired) noexcept { return val_.compare_exchange_strong(expected, desired); }
                T fetch_add(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_add(arg, order); }
                T fetch_sub(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_sub(arg, order); }
                T fetch_and(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_and(arg, order); }
                T fetch_or(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_or(arg, order); }
                T fetch_xor(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_xor(arg, order); }
                T operator+=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ += arg; }
                T operator-=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ -= arg; }
                T operator&=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ &= arg; }
                T operator|=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ |= arg; }
                T operator^=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ ^= arg; }
                T operator++() noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return ++val_; }
                T operator++(int) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_++; }
                T operator--() noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return --val_; }
                T operator--(int) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_--; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsVarargDef) {
        out.append(
            """

            #ifndef KXS_VARARG_DEFINED
            #define KXS_VARARG_DEFINED
            template <typename T>
            class KxsVararg : public std::vector<T> {
            public:
                KxsVararg() = default;
                KxsVararg(const KxsVararg&) = default;
                KxsVararg(KxsVararg&&) noexcept = default;
                KxsVararg& operator=(const KxsVararg&) = default;
                KxsVararg& operator=(KxsVararg&&) noexcept = default;
                KxsVararg(std::initializer_list<T> init) : std::vector<T>(init) {}
                KxsVararg(const std::vector<T>& items) : std::vector<T>(items) {}
                KxsVararg(std::vector<T>&& items) noexcept : std::vector<T>(std::move(items)) {}
                template <typename... Args>
                    requires (
                        sizeof...(Args) > 0 &&
                        !(sizeof...(Args) == 1 && ((
                            std::same_as<std::remove_cvref_t<Args>, KxsVararg<T>> ||
                            std::same_as<std::remove_cvref_t<Args>, std::vector<T>> ||
                            std::same_as<std::remove_cvref_t<Args>, std::initializer_list<T>>
                        ) || ...)) &&
                        (std::convertible_to<Args, T> && ...)
                    )
                KxsVararg(Args&&... args) {
                    this->reserve(sizeof...(Args));
                    (this->emplace_back(std::forward<Args>(args)), ...);
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSharedFromThisDef) {
        out.append(
            """

            #ifndef KXS_SHARED_FROM_THIS_DEFINED
            #define KXS_SHARED_FROM_THIS_DEFINED
            template <typename KxsSelf>
            inline std::shared_ptr<std::remove_cv_t<KxsSelf>> kxs_shared_from_this(KxsSelf* self) {
                using KxsClean = std::remove_cv_t<KxsSelf>;
                auto* mut_self = const_cast<KxsClean*>(self);
                if constexpr (requires { mut_self->CoroutineContext::weak_from_this(); }) {
                    if (auto sp = mut_self->CoroutineContext::weak_from_this().lock()) {
                        return std::dynamic_pointer_cast<KxsClean>(sp);
                    }
                } else if constexpr (requires { mut_self->weak_from_this(); }) {
                    if (auto sp = mut_self->weak_from_this().lock()) {
                        return std::dynamic_pointer_cast<KxsClean>(sp);
                    }
                }
                return std::shared_ptr<KxsClean>(mut_self, [](KxsClean*) noexcept {});
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsTimeMarkOpsDef) {
        out.append(
            """

            #ifndef KXS_TIME_MARK_OPS_DEFINED
            #define KXS_TIME_MARK_OPS_DEFINED
            namespace kotlin::time {
            inline auto operator<=>(Duration a, Duration b) noexcept {
                return a.compare_to(b) <=> 0;
            }
            inline bool operator==(Duration a, Duration b) noexcept {
                return a.compare_to(b) == 0;
            }
            #ifdef KOTLIN_TIME_TIME_SOURCE_HPP_
            inline Duration operator-(const ValueTimeMark& a, const ValueTimeMark& b) {
                return a.minus(b);
            }
            inline ValueTimeMark operator+(const ValueTimeMark& m, Duration d) {
                auto* p = const_cast<ValueTimeMark&>(m).plus(d);
                auto v = *p;
                delete p;
                return v;
            }
            inline ValueTimeMark operator-(const ValueTimeMark& m, Duration d) {
                auto* p = const_cast<ValueTimeMark&>(m).minus(d);
                auto v = *p;
                delete p;
                return v;
            }
            #ifndef KXS_ABSTRACT_LONG_TIME_SOURCE_DEFINED
            #define KXS_ABSTRACT_LONG_TIME_SOURCE_DEFINED
            class AbstractLongTimeSource : public TimeSource::WithComparableMarks {
            public:
                explicit AbstractLongTimeSource(DurationUnit unit) : unit_(unit) {}
                virtual ~AbstractLongTimeSource() = default;
                ComparableTimeMark* mark_now() override {
                    return new ValueTimeMark(
                        static_cast<ValueTimeMarkReading>(to_duration(read(), unit_).to_long(DurationUnit::NANOSECONDS)));
                }
            protected:
                virtual std::int64_t read() = 0;
            private:
                DurationUnit unit_;
            };
            #endif
            #endif
            } // namespace kotlin::time
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsDarwinDispatchHelpers) {
        out.append(
            """

            #if defined(__APPLE__) && !defined(KXS_DARWIN_DISPATCH_HELPERS_DEFINED)
            #define KXS_DARWIN_DISPATCH_HELPERS_DEFINED
            template <typename KxsFn>
            inline void kxs_dispatch_async(dispatch_queue_t queue, KxsFn&& fn) {
                using FnDecayed = std::decay_t<KxsFn>;
                auto* heap_fn = new FnDecayed(std::forward<KxsFn>(fn));
                dispatch_async_f(queue, heap_fn, [](void* ctx) {
                    auto* f = static_cast<FnDecayed*>(ctx);
                    (*f)();
                    delete f;
                });
            }
            template <typename KxsFn>
            inline CFRunLoopTimerRef kxs_cf_timer_create_with_handler(
                CFAllocatorRef allocator,
                CFAbsoluteTime fire_date,
                CFTimeInterval interval,
                CFOptionFlags flags,
                CFIndex order,
                KxsFn&& fn
            ) {
                using FnDecayed = std::decay_t<KxsFn>;
                auto* heap_fn = new FnDecayed(std::forward<KxsFn>(fn));
                CFRunLoopTimerContext ctx{};
                ctx.version = 0;
                ctx.info = heap_fn;
                ctx.release = [](const void* info) {
                    delete static_cast<const FnDecayed*>(info);
                };
                return CFRunLoopTimerCreate(
                    allocator,
                    fire_date,
                    interval,
                    flags,
                    order,
                    [](CFRunLoopTimerRef timer, void* info) {
                        (*static_cast<FnDecayed*>(info))(timer);
                    },
                    &ctx
                );
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsKotlinIteratorDefs) {
        out.append(
            """

            #ifndef KXS_KOTLIN_ITERATOR_DEFS_DEFINED
            #define KXS_KOTLIN_ITERATOR_DEFS_DEFINED
            template <typename T>
            class Iterator {
            public:
                virtual ~Iterator() = default;
                virtual bool has_next() const = 0;
                virtual T next() = 0;
            };
            template <typename T>
            class MutableIterator : public virtual Iterator<T> {
            public:
                virtual void remove() = 0;
            };
            template <typename T>
            class ListIterator : public virtual Iterator<T> {
            public:
                virtual bool has_previous() const = 0;
                virtual T previous() = 0;
                virtual std::int32_t next_index() const = 0;
                virtual std::int32_t previous_index() const = 0;
            };
            template <typename T>
            class MutableListIterator : public virtual ListIterator<T>, public virtual MutableIterator<T> {
            public:
                virtual void set(T element) = 0;
                virtual void add(T element) = 0;
            };
            template <typename E>
            class AbstractList {
            public:
                virtual ~AbstractList() = default;
                virtual std::int32_t size() const = 0;
                virtual E get(std::int32_t index) const = 0;
                bool is_empty() const { return size() == 0; }
            };
            template <typename E>
            class AbstractMutableList : public AbstractList<E> {
            public:
                virtual bool add(E element) = 0;
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsNullOperatorsDef) {
        out.append(
            """

            #ifndef KXS_NULL_OPERATORS_DEFINED
            #define KXS_NULL_OPERATORS_DEFINED
            template <typename KxsAny>
                requires std::same_as<KxsAny, std::any>
            inline bool operator==(const KxsAny& a, std::nullptr_t) noexcept {
                return !a.has_value() || a.type() == typeid(std::nullptr_t);
            }
            template <typename KxsOptT>
            constexpr bool operator==(const std::optional<KxsOptT>& o, std::nullptr_t) noexcept {
                return !o.has_value();
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCastHelpersDef) {
        out.append(
            """

            #ifndef KXS_CAST_HELPERS_DEFINED
            #define KXS_CAST_HELPERS_DEFINED
            template <typename T> struct kxs_is_shared_ptr : std::false_type {};
            template <typename T> struct kxs_is_shared_ptr<std::shared_ptr<T>> : std::true_type {};
            template <typename T> inline constexpr bool kxs_is_shared_ptr_v = kxs_is_shared_ptr<std::remove_cvref_t<T>>::value;
            template <typename T> struct kxs_is_optional : std::false_type {};
            template <typename T> struct kxs_is_optional<std::optional<T>> : std::true_type {};
            template <typename T> inline constexpr bool kxs_is_optional_v = kxs_is_optional<std::remove_cvref_t<T>>::value;
            template <typename T> struct kxs_pointee { using type = T; };
            template <typename T> struct kxs_pointee<std::shared_ptr<T>> { using type = T; };
            template <typename T> struct kxs_pointee<T*> { using type = T; };
            template <typename T> using kxs_pointee_t = std::remove_cv_t<typename kxs_pointee<std::remove_cvref_t<T>>::type>;

            template <typename KxsTarget, typename KxsSrc>
            inline bool kxs_is_instance(const KxsSrc& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using Pointee = kxs_pointee_t<TargetD>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_same_v<SrcD, std::nullptr_t>) {
                    return false;
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) return false;
                    if constexpr (!std::is_void_v<TargetD> && !std::is_abstract_v<TargetD>) {
                        if (std::any_cast<const TargetD>(std::addressof(v)) != nullptr) return true;
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<Pointee, TargetD> && !std::is_abstract_v<Pointee>) {
                        if (std::any_cast<const Pointee>(std::addressof(v)) != nullptr) return true;
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<TargetD, std::shared_ptr<Pointee>>) {
                        if (const auto* sp = std::any_cast<const std::shared_ptr<Pointee>>(std::addressof(v))) {
                            if (*sp) return true;
                        }
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<TargetD, Pointee*>) {
                        if (const auto* ptr = std::any_cast<Pointee* const>(std::addressof(v))) {
                            if (*ptr != nullptr) return true;
                        }
                    }
                    return false;
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    return v.has_value() && kxs_is_instance<KxsTarget>(*v);
                } else if constexpr (kxs_is_shared_ptr_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<typename SrcD::element_type>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>)) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v.get()) != nullptr;
                    else return false;
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>)) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v) != nullptr;
                    else return false;
                } else {
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcD> && (std::is_base_of_v<Pointee, SrcD> || std::is_same_v<Pointee, SrcD>)) return true;
                    else if constexpr (!std::is_void_v<SrcD> && std::is_polymorphic_v<SrcD> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(std::addressof(v)) != nullptr;
                    else return false;
                }
            }

            template <typename KxsTargetD>
            inline KxsTargetD kxs_cast_default_or_throw() {
                if constexpr (std::is_default_constructible_v<KxsTargetD>) {
                    return KxsTargetD{};
                } else {
                    throw std::runtime_error("ClassCastException");
                }
            }

            template <typename KxsTargetD>
            inline KxsTargetD kxs_safe_cast_null() {
                if constexpr (std::is_pointer_v<KxsTargetD> || kxs_is_shared_ptr_v<KxsTargetD> || std::is_same_v<KxsTargetD, std::nullptr_t>) {
                    return nullptr;
                } else if constexpr (kxs_is_optional_v<KxsTargetD> || std::is_same_v<KxsTargetD, std::any>) {
                    return KxsTargetD{};
                } else if constexpr (std::is_default_constructible_v<KxsTargetD>) {
                    return KxsTargetD{};
                } else {
                    throw std::runtime_error("ClassCastException");
                }
            }

            template <typename KxsTarget, typename KxsSrc>
            inline KxsTarget kxs_safe_as_cast(KxsSrc&& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_abstract_v<TargetD>) {
                    static_assert(!std::is_abstract_v<TargetD>, "abstract target must use shared_ptr or pointer");
                } else if constexpr (std::is_same_v<SrcD, std::nullptr_t>) {
                    return kxs_safe_cast_null<TargetD>();
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                        return kxs_safe_cast_null<TargetD>();
                    }
                    if constexpr (!std::is_void_v<TargetD>) {
                        if (const auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    }
                    if constexpr (kxs_is_optional_v<TargetD>) {
                        using Inner = typename TargetD::value_type;
                        if constexpr (!std::is_void_v<Inner> && !std::is_abstract_v<Inner>) {
                            if (const auto* p = std::any_cast<const Inner>(std::addressof(v))) return TargetD(*p);
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if (const auto* pp = std::any_cast<DstEl* const>(std::addressof(v))) {
                            return *pp ? std::shared_ptr<DstEl>(std::shared_ptr<void>{}, *pp) : TargetD{};
                        }
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (!std::is_void_v<DstEl>) {
                            if (const auto* sp = std::any_cast<const std::shared_ptr<DstEl>>(std::addressof(v))) {
                                return sp->get();
                            }
                        }
                    }
                    return kxs_safe_cast_null<TargetD>();
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    if (!v.has_value()) return kxs_safe_cast_null<TargetD>();
                    return kxs_safe_as_cast<KxsTarget>(*v);
                } else if constexpr (kxs_is_shared_ptr_v<SrcD>) {
                    using SrcEl = typename SrcD::element_type;
                    if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) {
                            return std::static_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        } else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                            return std::dynamic_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        } else {
                            return kxs_safe_cast_null<TargetD>();
                        }
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else return kxs_safe_cast_null<TargetD>();
                    } else {
                        return kxs_safe_cast_null<TargetD>();
                    }
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if (!v) return kxs_safe_cast_null<TargetD>();
                    if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_same_v<SrcEl, void>) return static_cast<TargetD>(const_cast<void*>(v));
                        else if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcEl*>(v));
                        else return kxs_safe_cast_null<TargetD>();
                    } else if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) {
                            return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, const_cast<DstEl*>(static_cast<const DstEl*>(v)));
                        } else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                            if (auto* d = dynamic_cast<DstEl*>(const_cast<SrcEl*>(v))) {
                                return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, d);
                            }
                            return kxs_safe_cast_null<TargetD>();
                        } else {
                            return kxs_safe_cast_null<TargetD>();
                        }
                    } else if constexpr (std::is_same_v<SrcEl, void>) {
                        if constexpr (kxs_is_optional_v<TargetD>) {
                            return TargetD(*static_cast<typename TargetD::value_type*>(const_cast<void*>(v)));
                        } else {
                            return *static_cast<TargetD*>(const_cast<void*>(v));
                        }
                    } else {
                        return kxs_safe_cast_null<TargetD>();
                    }
                } else if constexpr (std::is_same_v<SrcD, TargetD> || std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (std::is_pointer_v<TargetD> && std::is_polymorphic_v<SrcD>) {
                    using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                    if constexpr (std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcD*>(std::addressof(v)));
                    else return kxs_safe_cast_null<TargetD>();
                } else {
                    return kxs_safe_cast_null<TargetD>();
                }
            }

            template <typename KxsTarget, typename KxsSrc>
            inline KxsTarget kxs_as_cast(KxsSrc&& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_abstract_v<TargetD>) {
                    static_assert(!std::is_abstract_v<TargetD>, "abstract target must use shared_ptr or pointer");
                } else if constexpr (std::is_same_v<SrcD, TargetD>) {
                    return std::forward<KxsSrc>(v);
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                        return kxs_cast_default_or_throw<TargetD>();
                    }
                    if (auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    throw std::runtime_error("ClassCastException");
                } else if constexpr (std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (kxs_is_shared_ptr_v<SrcD> && kxs_is_shared_ptr_v<TargetD>) {
                    if (!v) return kxs_cast_default_or_throw<TargetD>();
                    using SrcEl = typename SrcD::element_type;
                    using DstEl = typename TargetD::element_type;
                    if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                        auto res = std::dynamic_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        if (!res) throw std::runtime_error("ClassCastException");
                        return res;
                    } else {
                        throw std::runtime_error("ClassCastException");
                    }
                } else if constexpr (std::is_pointer_v<SrcD> && std::is_same_v<std::remove_cv_t<std::remove_pointer_t<SrcD>>, void>) {
                    if constexpr (kxs_is_optional_v<TargetD>) {
                        using Inner = typename TargetD::value_type;
                        return v ? TargetD(*static_cast<Inner*>(const_cast<void*>(v))) : TargetD{std::nullopt};
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        return static_cast<TargetD>(const_cast<void*>(v));
                    } else {
                        if (!v) throw std::runtime_error("NullPointerException");
                        return *static_cast<TargetD*>(const_cast<void*>(v));
                    }
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    if (!v) return kxs_cast_default_or_throw<TargetD>();
                    return kxs_safe_as_cast<KxsTarget>(std::forward<KxsSrc>(v));
                } else {
                    return kxs_safe_as_cast<KxsTarget>(std::forward<KxsSrc>(v));
                }
            }

            template <typename L, typename RFn>
            inline decltype(auto) kxs_elvis(L&& lhs, RFn&& rhs_fn) {
                using LDecay = std::remove_cvref_t<L>;
                using RRaw = std::remove_cvref_t<decltype(rhs_fn())>;
                if constexpr (std::is_void_v<decltype(rhs_fn())>) {
                    if constexpr (std::is_same_v<LDecay, std::any>) {
                        if (!lhs.has_value() || lhs.type() == typeid(std::nullptr_t)) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    } else if constexpr (kxs_is_optional_v<LDecay>) {
                        if (!lhs.has_value()) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    } else {
                        if (!lhs) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    }
                    return;
                } else if constexpr (std::is_same_v<LDecay, std::any>) {
                    return (lhs.has_value() && lhs.type() != typeid(std::nullptr_t))
                        ? LDecay(std::forward<L>(lhs))
                        : std::any(std::forward<RFn>(rhs_fn)());
                } else if constexpr (kxs_is_optional_v<LDecay>) {
                    if constexpr (kxs_is_optional_v<RRaw> || std::is_null_pointer_v<RRaw>) {
                        return lhs.has_value() ? LDecay(std::forward<L>(lhs)) : LDecay(std::forward<RFn>(rhs_fn)());
                    } else if constexpr (std::is_convertible_v<RRaw, typename LDecay::value_type>) {
                        return lhs.has_value() ? typename LDecay::value_type(*std::forward<L>(lhs)) : typename LDecay::value_type(std::forward<RFn>(rhs_fn)());
                    } else {
                        return lhs.has_value() ? *std::forward<L>(lhs) : std::forward<RFn>(rhs_fn)();
                    }
                } else {
                    if constexpr (
                        std::is_same_v<LDecay, std::exception_ptr> &&
                        !std::is_same_v<RRaw, std::exception_ptr>
                    ) {
                        return lhs ? std::forward<L>(lhs) : std::make_exception_ptr(std::forward<RFn>(rhs_fn)());
                    } else if constexpr (std::is_convertible_v<LDecay, RRaw>) {
                        return lhs ? RRaw(std::forward<L>(lhs)) : std::forward<RFn>(rhs_fn)();
                    } else if constexpr (std::is_convertible_v<RRaw, LDecay>) {
                        return lhs ? LDecay(std::forward<L>(lhs)) : LDecay(std::forward<RFn>(rhs_fn)());
                    } else {
                        return lhs ? std::forward<L>(lhs) : std::forward<RFn>(rhs_fn)();
                    }
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsAnyEqDef) {
        out.append(
            """
            #ifndef KXS_ANY_EQ_DEFINED
            #define KXS_ANY_EQ_DEFINED
            template <typename KxsT>
            inline bool kxs_is_null_any_or_ptr(const KxsT& v) noexcept {
                using D = std::remove_cvref_t<KxsT>;
                if constexpr (std::is_null_pointer_v<D>) {
                    return true;
                } else if constexpr (std::is_same_v<D, std::any>) {
                    return !v.has_value() || v.type() == typeid(std::nullptr_t);
                } else if constexpr (std::is_pointer_v<D> || kxs_is_shared_ptr_v<D>) {
                    return v == nullptr;
                } else if constexpr (kxs_is_optional_v<D>) {
                    return !v.has_value();
                } else {
                    return false;
                }
            }

            template <typename KxsL, typename KxsR>
            inline bool kxs_any_eq(const KxsL& lhs, const KxsR& rhs) {
                using L = std::remove_cvref_t<KxsL>;
                using R = std::remove_cvref_t<KxsR>;
                if constexpr (std::is_same_v<L, std::any> && std::is_same_v<R, std::any>) {
                    const bool ln = kxs_is_null_any_or_ptr(lhs);
                    const bool rn = kxs_is_null_any_or_ptr(rhs);
                    if (ln || rn) return ln && rn;
                    if (lhs.type() != rhs.type()) return false;
                    if (lhs.type() == typeid(bool)) {
                        return std::any_cast<bool>(lhs) == std::any_cast<bool>(rhs);
                    }
                    if (lhs.type() == typeid(std::int32_t)) {
                        return std::any_cast<std::int32_t>(lhs) == std::any_cast<std::int32_t>(rhs);
                    }
                    if (lhs.type() == typeid(std::int64_t)) {
                        return std::any_cast<std::int64_t>(lhs) == std::any_cast<std::int64_t>(rhs);
                    }
                    if (lhs.type() == typeid(double)) {
                        return std::any_cast<double>(lhs) == std::any_cast<double>(rhs);
                    }
                    if (lhs.type() == typeid(std::string)) {
                        const auto& ls = std::any_cast<const std::string&>(lhs);
                        const auto& rs = std::any_cast<const std::string&>(rhs);
                        return ls == rs;
                    }
                    if (lhs.type() == typeid(void*)) {
                        return std::any_cast<void*>(lhs) == std::any_cast<void*>(rhs);
                    }
                    if (lhs.type() == typeid(const void*)) {
                        return std::any_cast<const void*>(lhs) == std::any_cast<const void*>(rhs);
                    }
                    return std::addressof(lhs) == std::addressof(rhs);
                } else if constexpr (std::is_same_v<L, std::any>) {
                    if (kxs_is_null_any_or_ptr(lhs)) return kxs_is_null_any_or_ptr(rhs);
                    if (kxs_is_null_any_or_ptr(rhs)) return false;
                    if constexpr (!std::is_abstract_v<R> && !std::is_array_v<R> && !std::is_function_v<R>) {
                        if (const auto* lp = std::any_cast<const R>(std::addressof(lhs))) {
                            if constexpr (requires { *lp == rhs; }) return *lp == rhs;
                            else return false;
                        }
                    }
                    if constexpr (std::is_pointer_v<R>) {
                        using Pt = std::remove_cv_t<std::remove_pointer_t<R>>;
                        if constexpr (!std::is_void_v<Pt> && !std::is_function_v<Pt>) {
                            if (const auto* lsp = std::any_cast<const std::shared_ptr<Pt>>(std::addressof(lhs))) {
                                return lsp->get() == rhs;
                            }
                            if constexpr (std::is_polymorphic_v<Pt>) {
                                if (const auto* lvp = std::any_cast<void* const>(std::addressof(lhs))) {
                                    return *lvp == static_cast<const void*>(rhs);
                                }
                            }
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<R>) {
                        using Pt = std::remove_cv_t<typename R::element_type>;
                        if constexpr (!std::is_void_v<Pt>) {
                            if (const auto* lptr = std::any_cast<Pt* const>(std::addressof(lhs))) {
                                return *lptr == rhs.get();
                            }
                        }
                    } else if constexpr (kxs_is_optional_v<R>) {
                        return kxs_any_eq(lhs, *rhs);
                    } else if constexpr (std::is_convertible_v<R, std::string>) {
                        if (const auto* ls = std::any_cast<const std::string>(std::addressof(lhs))) {
                            return *ls == std::string(rhs);
                        }
                    }
                    return false;
                } else if constexpr (std::is_same_v<R, std::any>) {
                    return kxs_any_eq(rhs, lhs);
                } else if constexpr (std::is_null_pointer_v<L> || std::is_null_pointer_v<R>) {
                    return kxs_is_null_any_or_ptr(lhs) && kxs_is_null_any_or_ptr(rhs);
                } else if constexpr (requires { lhs == rhs; }) {
                    return lhs == rhs;
                } else if constexpr (kxs_is_shared_ptr_v<L> && std::is_pointer_v<R>) {
                    return static_cast<const void*>(lhs.get()) == static_cast<const void*>(rhs);
                } else if constexpr (std::is_pointer_v<L> && kxs_is_shared_ptr_v<R>) {
                    return static_cast<const void*>(lhs) == static_cast<const void*>(rhs.get());
                } else {
                    return std::addressof(lhs) == std::addressof(rhs);
                }
            }

            template <typename KxsL, typename KxsR>
            inline bool kxs_any_ref_eq(const KxsL& lhs, const KxsR& rhs) {
                using L = std::remove_cvref_t<KxsL>;
                using R = std::remove_cvref_t<KxsR>;
                if constexpr (std::is_same_v<L, std::any> && std::is_same_v<R, std::any>) {
                    return kxs_any_eq(lhs, rhs);
                } else if constexpr (std::is_same_v<L, std::any>) {
                    if (kxs_is_null_any_or_ptr(lhs)) return kxs_is_null_any_or_ptr(rhs);
                    if (kxs_is_null_any_or_ptr(rhs)) return false;
                    if constexpr (!std::is_abstract_v<R> && !std::is_array_v<R> && !std::is_function_v<R>) {
                        if (const auto* lp = std::any_cast<const R>(std::addressof(lhs))) {
                            if constexpr (requires { *lp == rhs; }) return *lp == rhs;
                            else return lp == std::addressof(rhs);
                        }
                    }
                    if constexpr (std::is_pointer_v<R>) {
                        using Pt = std::remove_cv_t<std::remove_pointer_t<R>>;
                        if constexpr (!std::is_void_v<Pt> && !std::is_function_v<Pt>) {
                            if (const auto* lsp = std::any_cast<const std::shared_ptr<Pt>>(std::addressof(lhs))) {
                                return lsp->get() == rhs;
                            }
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<R>) {
                        using Pt = std::remove_cv_t<typename R::element_type>;
                        if constexpr (!std::is_void_v<Pt>) {
                            if (const auto* lptr = std::any_cast<Pt* const>(std::addressof(lhs))) {
                                return *lptr == rhs.get();
                            }
                        }
                    }
                    return false;
                } else if constexpr (std::is_same_v<R, std::any>) {
                    return kxs_any_ref_eq(rhs, lhs);
                } else if constexpr (std::is_same_v<L, R> && (std::is_arithmetic_v<L> || std::is_enum_v<L>)) {
                    return lhs == rhs;
                } else {
                    auto kxs_ptr = [](const auto& v) -> const void* {
                        using V = std::remove_cvref_t<decltype(v)>;
                        if constexpr (std::is_null_pointer_v<V>) return nullptr;
                        else if constexpr (std::is_pointer_v<V>) return static_cast<const void*>(v);
                        else if constexpr (requires { v.get(); }) return static_cast<const void*>(v.get());
                        else if constexpr (requires { typename V::value_type; v.has_value(); *v; }) {
                            return v.has_value() ? static_cast<const void*>(std::addressof(*v)) : nullptr;
                        } else return static_cast<const void*>(std::addressof(v));
                    };
                    return kxs_ptr(lhs) == kxs_ptr(rhs);
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsNullUnwrapDef) {
        out.append(
            """

            #ifndef KXS_NULL_UNWRAP_DEFINED
            #define KXS_NULL_UNWRAP_DEFINED
            template <typename KxsVal>
            inline bool kxs_is_null(const KxsVal& v) {
                using KxsDecayed = std::remove_cvref_t<KxsVal>;
                if constexpr (std::is_same_v<KxsDecayed, std::any>) {
                    return !v.has_value() || v.type() == typeid(std::nullptr_t);
                } else if constexpr (std::is_null_pointer_v<KxsDecayed>) {
                    return true;
                } else if constexpr (requires { v.has_value(); }) {
                    return !v.has_value();
                } else if constexpr (std::is_pointer_v<KxsDecayed> || requires { v.get(); }) {
                    return !static_cast<bool>(v);
                } else if constexpr (std::is_same_v<KxsDecayed, std::exception_ptr>) {
                    return !static_cast<bool>(v);
                } else if constexpr (requires { static_cast<bool>(v); } && !std::is_arithmetic_v<KxsDecayed>) {
                    return !static_cast<bool>(v);
                } else {
                    return false;
                }
            }
            template <typename KxsOpt>
            constexpr decltype(auto) kxs_unwrap_opt(KxsOpt&& v) {
                using KxsDecayed = std::remove_cvref_t<KxsOpt>;
                if constexpr (requires { v.has_value(); *v; } && !std::is_same_v<KxsDecayed, std::any>) {
                    return *std::forward<KxsOpt>(v);
                } else {
                    return std::forward<KxsOpt>(v);
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsResultHelpersDef) {
        out.append(
            """

            #ifndef KXS_RESULT_HELPERS_DEFINED
            #define KXS_RESULT_HELPERS_DEFINED
            template <typename KxsExc>
            inline std::exception_ptr kxs_to_exception_ptr(KxsExc&& e) {
                using KxsExcD = std::remove_cvref_t<KxsExc>;
                if constexpr (std::is_same_v<KxsExcD, std::exception_ptr>) {
                    return std::forward<KxsExc>(e);
                } else if constexpr (std::is_null_pointer_v<KxsExcD>) {
                    return std::exception_ptr{};
                } else if constexpr (std::is_same_v<KxsExcD, std::any>) {
                    if (!e.has_value() || e.type() == typeid(std::nullptr_t)) {
                        return std::exception_ptr{};
                    }
                    if (const auto* ep = std::any_cast<std::exception_ptr>(std::addressof(e))) {
                        return *ep;
                    }
                    return std::make_exception_ptr(e);
                } else if constexpr (requires { typename KxsExcD::value_type; e.has_value(); *e; }) {
                    return e.has_value() ? kxs_to_exception_ptr(*std::forward<KxsExc>(e)) : std::exception_ptr{};
                } else {
                    return std::make_exception_ptr(std::forward<KxsExc>(e));
                }
            }
            struct KxsResultFailure {
                std::exception_ptr e;
                constexpr bool is_success() const noexcept { return false; }
                constexpr bool is_failure() const noexcept { return true; }
                std::exception_ptr exception_or_null() const noexcept { return e; }
                [[noreturn]] void get_or_throw() const { std::rethrow_exception(e); }
                template <typename KxsResT>
                operator kotlinx::coroutines::Result<KxsResT>() const {
                    return kotlinx::coroutines::Result<KxsResT>::failure(e);
                }
            };
            template <typename KxsRes, typename KxsFn>
            inline decltype(auto) kxs_result_get_or_else(KxsRes&& r, KxsFn&& on_failure) {
                if constexpr (std::is_void_v<decltype(r.get_or_throw())>) {
                    if (r.is_success()) {
                        if constexpr (std::is_void_v<decltype(on_failure(r.exception_or_null()))>) {
                            return;
                        } else {
                            return kotlinx::coroutines::Unit{};
                        }
                    }
                    return on_failure(r.exception_or_null());
                } else {
                    using ValT = std::decay_t<decltype(r.get_or_throw())>;
                    using AltT = std::decay_t<decltype(on_failure(r.exception_or_null()))>;
                    if constexpr (std::is_void_v<AltT>) {
                        if (!r.is_success()) {
                            on_failure(r.exception_or_null());
                        }
                        return r.get_or_throw();
                    } else if constexpr (std::is_same_v<ValT, AltT>) {
                        if (r.is_success()) return r.get_or_throw();
                        return on_failure(r.exception_or_null());
                    } else if constexpr (requires { typename std::common_type_t<ValT, AltT>; }) {
                        using RetT = std::common_type_t<ValT, AltT>;
                        if (r.is_success()) return static_cast<RetT>(r.get_or_throw());
                        return static_cast<RetT>(on_failure(r.exception_or_null()));
                    } else {
                        if (r.is_success()) return std::any(r.get_or_throw());
                        return std::any(on_failure(r.exception_or_null()));
                    }
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSuspendBoxHelpersDef) {
        out.append(
            """

            #ifndef KXS_SUSPEND_BOX_HELPERS_DEFINED
            #define KXS_SUSPEND_BOX_HELPERS_DEFINED
            template <typename KxsT>
            struct kxs_box_is_optional : std::false_type {};
            template <typename KxsT>
            struct kxs_box_is_optional<std::optional<KxsT>> : std::true_type {};

            template <typename KxsBoxT, typename KxsVal>
            inline void* kxs_box_suspend_result(KxsVal&& v) {
                using BoxD = std::remove_cvref_t<KxsBoxT>;
                using ValD = std::remove_cvref_t<KxsVal>;
                if constexpr (std::is_same_v<ValD, void*>) {
                    if (kotlin::coroutines::intrinsics::is_coroutine_suspended(v)) {
                        return v;
                    }
                    if constexpr (std::is_void_v<BoxD>) {
                        return nullptr;
                    } else if constexpr (std::is_default_constructible_v<BoxD>) {
                        return v ? v : static_cast<void*>(new BoxD{});
                    } else {
                        return v;
                    }
                } else if constexpr (std::is_same_v<ValD, std::any>) {
                    if (const auto* kxs_raw_p = std::any_cast<void*>(std::addressof(v))) {
                        if (kotlin::coroutines::intrinsics::is_coroutine_suspended(*kxs_raw_p)) {
                            return *kxs_raw_p;
                        }
                    }
                    if constexpr (std::is_void_v<BoxD>) {
                        return nullptr;
                    } else if constexpr (std::is_same_v<BoxD, std::any>) {
                        return static_cast<void*>(new std::any(std::forward<KxsVal>(v)));
                    } else if constexpr (std::is_same_v<BoxD, kotlin::Unit>) {
                        return static_cast<void*>(new kotlin::Unit{});
                    } else {
                        if (const auto* kxs_val_p = std::any_cast<BoxD>(std::addressof(v))) {
                            return static_cast<void*>(new BoxD(*kxs_val_p));
                        }
                        if constexpr (kxs_box_is_optional<BoxD>::value) {
                            using InnerT = typename BoxD::value_type;
                            if (const auto* kxs_inner_p = std::any_cast<InnerT>(std::addressof(v))) {
                                return static_cast<void*>(new BoxD(*kxs_inner_p));
                            }
                            if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                                return static_cast<void*>(new BoxD{});
                            }
                        }
                        if constexpr (std::is_default_constructible_v<BoxD>) {
                            if (!v.has_value()) {
                                return static_cast<void*>(new BoxD{});
                            }
                        }
                        throw std::runtime_error("ClassCastException");
                    }
                } else if constexpr (std::is_void_v<BoxD>) {
                    return nullptr;
                } else {
                    return static_cast<void*>(new BoxD(std::forward<KxsVal>(v)));
                }
            }

            template <typename KxsValT>
            inline void* kxs_unit_suspend_result(KxsValT&& v) {
                using ValD = std::remove_cvref_t<KxsValT>;
                if constexpr (std::is_same_v<ValD, void*>) {
                    return kotlin::coroutines::intrinsics::is_coroutine_suspended(v) ? v : nullptr;
                } else if constexpr (std::is_same_v<ValD, std::any>) {
                    if (const auto* p = std::any_cast<void*>(std::addressof(v))) {
                        if (kotlin::coroutines::intrinsics::is_coroutine_suspended(*p)) {
                            return *p;
                        }
                    }
                    return nullptr;
                } else {
                    return nullptr;
                }
            }

            template <typename KxsContElem = void*, template <typename> class KxsContTmpl>
            inline std::shared_ptr<KxsContTmpl<KxsContElem>> kxs_wrap_raw_continuation(KxsContTmpl<KxsContElem>* raw) {
                if (!raw) return nullptr;
                return std::shared_ptr<KxsContTmpl<KxsContElem>>(raw, [](KxsContTmpl<KxsContElem>*) {});
            }

            template <typename KxsT = kotlin::Unit, typename KxsBlock>
            [[clang::annotate("suspend"), clang::annotate("kxs_implicit_continuation"), clang::annotate("kxs_boxed_result")]]
            std::conditional_t<std::is_void_v<KxsT>, kotlin::Unit, KxsT>
            kxs_suspend_coroutine_unintercepted_or_return(KxsBlock&& block);

            template <typename KxsT = kotlin::Unit, typename KxsBlock>
            [[clang::annotate("suspend")]]
            inline void* kxs_suspend_coroutine_unintercepted_or_return(
                KxsBlock&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion
            ) {
                using SafeT = std::conditional_t<std::is_void_v<KxsT>, kotlin::Unit, KxsT>;
                std::shared_ptr<kotlin::coroutines::Continuation<KxsT>> kxs_typed_ucont;
                if constexpr (std::is_same_v<KxsT, void*>) {
                    kxs_typed_ucont = _kxs_completion;
                } else {
                    kxs_typed_ucont = std::make_shared<kotlin::coroutines::FunctionalContinuation<KxsT>>(
                        _kxs_completion ? _kxs_completion->get_context() : nullptr,
                        [_kxs_completion](kotlin::coroutines::Result<KxsT> r) {
                            if (!_kxs_completion) return;
                            if (r.is_failure()) {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));
                            } else if constexpr (std::is_same_v<KxsT, kotlin::Unit> || std::is_void_v<KxsT>) {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));
                            } else {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new SafeT(r.get_or_throw())));
                            }
                        });
                }
                if constexpr (std::is_void_v<decltype(block(kxs_typed_ucont))>) {
                    block(kxs_typed_ucont);
                    if constexpr (std::is_same_v<SafeT, kotlin::Unit>) {
                        return static_cast<void*>(new kotlin::Unit{});
                    } else if constexpr (std::is_default_constructible_v<SafeT>) {
                        return static_cast<void*>(new SafeT{});
                    } else {
                        return nullptr;
                    }
                } else {
                    return kxs_box_suspend_result<SafeT>(block(kxs_typed_ucont));
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCoroutineIntrinsicsBridgeDef) {
        out.append(
            """

            #ifndef KXS_COROUTINE_INTRINSICS_BRIDGE_DEFINED
            #define KXS_COROUTINE_INTRINSICS_BRIDGE_DEFINED
            template <typename KxsContT>
            kotlin::coroutines::Continuation<KxsContT>* kxs_cont_base_probe(
                kotlin::coroutines::Continuation<KxsContT>*
            );

            template <typename KxsPtr>
            concept kxs_derived_cont_ptr =
                requires(const KxsPtr& p) { kxs_cont_base_probe(p.operator->()); };

            template <typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> kxs_intercepted(
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> cont
            ) {
                auto erased = kotlin::coroutines::intrinsics::intercepted(
                    kotlin::coroutines::to_void_continuation(std::move(cont))
                );
                return kotlin::coroutines::internal::result_box_completion<KxsContT>(std::move(erased));
            }

            template <typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> kxs_intercepted(
                kotlin::coroutines::Continuation<KxsContT>* raw
            ) {
                return kxs_intercepted(kxs_wrap_raw_continuation(raw));
            }

            template <typename KxsFn, typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                kotlin::coroutines::intrinsics::ErasedSuspendFunction erased;
                if constexpr (std::is_invocable_v<FnD&, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>> c) mutable -> void* {
                        return b(std::move(c));
                    };
                } else if constexpr (std::is_invocable_v<FnD&, kotlin::coroutines::Continuation<KxsContT>*>) {
                    erased = kotlin::coroutines::intrinsics::erase_suspend_function<KxsContT>(
                        std::function<void*(kotlin::coroutines::Continuation<KxsContT>*)>(std::forward<KxsFn>(block))
                    );
                } else {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>>) mutable -> void* {
                        using SafeT = std::conditional_t<std::is_void_v<KxsContT>, kotlin::Unit, KxsContT>;
                        if constexpr (std::is_void_v<decltype(b())>) {
                            b();
                            return kxs_box_suspend_result<SafeT>(kotlin::Unit{});
                        } else {
                            return kxs_box_suspend_result<SafeT>(b());
                        }
                    };
                }
                auto raw_cont = kotlin::coroutines::intrinsics::create_coroutine_unintercepted(
                    std::move(erased),
                    kotlin::coroutines::to_void_continuation(std::move(completion))
                );
                return kotlin::coroutines::internal::result_box_completion<kotlin::Unit>(std::move(raw_cont));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                using RecvD = std::decay_t<KxsRecv>;
                if constexpr (std::is_invocable_v<FnD&, RecvD, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            std::shared_ptr<kotlin::coroutines::Continuation<void*>> c
                        ) mutable -> void* { return b(r, std::move(c)); },
                        std::move(completion)
                    );
                } else if constexpr (std::is_invocable_v<FnD&, RecvD, kotlin::coroutines::Continuation<KxsContT>*>) {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            kotlin::coroutines::Continuation<KxsContT>* c
                        ) mutable -> void* { return b(r, c); },
                        std::move(completion)
                    );
                } else {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)]() mutable -> decltype(auto) {
                            return b(r);
                        },
                        std::move(completion)
                    );
                }
            }

            template <typename KxsFn, typename KxsContT>
            inline void* kxs_start_coroutine_unintercepted_or_return(
                KxsFn&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                kotlin::coroutines::intrinsics::ErasedSuspendFunction erased;
                if constexpr (std::is_invocable_v<FnD&, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>> c) mutable -> void* {
                        return b(std::move(c));
                    };
                } else if constexpr (std::is_invocable_v<FnD&, kotlin::coroutines::Continuation<KxsContT>*>) {
                    erased = kotlin::coroutines::intrinsics::erase_suspend_function<KxsContT>(
                        std::function<void*(kotlin::coroutines::Continuation<KxsContT>*)>(std::forward<KxsFn>(block))
                    );
                } else {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>>) mutable -> void* {
                        using SafeT = std::conditional_t<std::is_void_v<KxsContT>, kotlin::Unit, KxsContT>;
                        if constexpr (std::is_void_v<decltype(b())>) {
                            b();
                            return kxs_box_suspend_result<SafeT>(kotlin::Unit{});
                        } else {
                            return kxs_box_suspend_result<SafeT>(b());
                        }
                    };
                }
                return kotlin::coroutines::intrinsics::start_coroutine_unintercepted_or_return(
                    std::move(erased),
                    kotlin::coroutines::to_void_continuation(std::move(completion))
                );
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContT>
            inline void* kxs_start_coroutine_unintercepted_or_return(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                using RecvD = std::decay_t<KxsRecv>;
                if constexpr (std::is_invocable_v<FnD&, RecvD, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            std::shared_ptr<kotlin::coroutines::Continuation<void*>> c
                        ) mutable -> void* { return b(r, std::move(c)); },
                        std::move(completion)
                    );
                } else if constexpr (std::is_invocable_v<FnD&, RecvD, kotlin::coroutines::Continuation<KxsContT>*>) {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            kotlin::coroutines::Continuation<KxsContT>* c
                        ) mutable -> void* { return b(r, c); },
                        std::move(completion)
                    );
                } else {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)]() mutable -> decltype(auto) {
                            return b(r);
                        },
                        std::move(completion)
                    );
                }
            }

            template <typename KxsFn, typename KxsContObj>
                requires requires(KxsContObj* p) { kxs_cont_base_probe(p); }
            inline void kxs_start_coroutine(
                KxsFn&& block,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                kxs_intercepted(
                    kxs_create_coroutine_unintercepted(std::forward<KxsFn>(block), std::move(base_comp))
                )->resume_with(kotlin::coroutines::Result<kotlin::Unit>::success(kotlin::Unit{}));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContObj>
                requires requires(KxsContObj* p) { kxs_cont_base_probe(p); }
            inline void kxs_start_coroutine(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                kxs_intercepted(
                    kxs_create_coroutine_unintercepted(
                        std::forward<KxsFn>(block),
                        std::forward<KxsRecv>(receiver),
                        std::move(base_comp)
                    )
                )->resume_with(kotlin::coroutines::Result<kotlin::Unit>::success(kotlin::Unit{}));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContObj>
                requires (!std::is_same_v<std::remove_cv_t<KxsContObj>, kotlin::coroutines::Continuation<void*>> &&
                    requires(KxsContObj* p) { kxs_cont_base_probe(p); })
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                using ValT = decltype([](kotlin::coroutines::Continuation<void*>*) { return nullptr; }(static_cast<BaseCont*>(nullptr)));
                (void)sizeof(ValT);
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                return kxs_create_coroutine_unintercepted(
                    std::forward<KxsFn>(block),
                    std::forward<KxsRecv>(receiver),
                    std::move(base_comp)
                );
            }

            template <typename KxsCont, typename KxsExc>
            inline void kxs_resume_with_exception(KxsCont&& cont, KxsExc&& exc) {
                auto kxs_e = kxs_to_exception_ptr(std::forward<KxsExc>(exc));
                if constexpr (requires { cont->resume_with_exception(kxs_e); }) {
                    cont->resume_with_exception(std::move(kxs_e));
                } else if constexpr (requires { cont.resume_with_exception(kxs_e); }) {
                    cont.resume_with_exception(std::move(kxs_e));
                } else if constexpr (requires { *cont; }) {
                    kotlin::coroutines::resume_with_exception(*cont, std::move(kxs_e));
                } else {
                    kotlin::coroutines::resume_with_exception(cont, std::move(kxs_e));
                }
            }

            template <typename KxsCont, typename KxsVal>
            inline void kxs_resume_cont(KxsCont&& cont, KxsVal&& val) {
                if constexpr (requires { cont->resume(std::forward<KxsVal>(val)); }) {
                    cont->resume(std::forward<KxsVal>(val));
                } else if constexpr (requires { cont.resume(std::forward<KxsVal>(val)); }) {
                    cont.resume(std::forward<KxsVal>(val));
                } else if constexpr (requires { *cont; }) {
                    kotlin::coroutines::resume(*cont, std::forward<KxsVal>(val));
                } else {
                    kotlin::coroutines::resume(cont, std::forward<KxsVal>(val));
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsIterableHelpersDef) {
        out.append(
            """

            #ifndef KXS_ITERABLE_HELPERS_DEFINED
            #define KXS_ITERABLE_HELPERS_DEFINED
            template <typename KxsPtr>
            struct KxsPtrRange {
                KxsPtr ptr;
                auto begin() const { return (*ptr).begin(); }
                auto end() const { return (*ptr).end(); }
            };
            template <typename T>
                requires requires(T& r) { std::begin(r); std::end(r); }
            inline T& kxs_iterable_ref(T& x) noexcept {
                return x;
            }
            template <typename T>
            inline auto kxs_iterable_ref(T&& x) {
                if constexpr (requires { std::begin(x); std::end(x); }) {
                    return std::decay_t<T>(std::move(x));
                } else if constexpr (requires { (*x).begin(); (*x).end(); }) {
                    return KxsPtrRange<std::decay_t<T>>{std::forward<T>(x)};
                } else if constexpr (requires { x->next_node(); x->for_each([](decltype(x->next_node())) {}); }) {
                    using ElemT = std::decay_t<decltype(x->next_node())>;
                    std::vector<ElemT> v;
                    if (x) {
                        x->for_each([&](ElemT n) { v.push_back(std::move(n)); });
                    }
                    return v;
                } else if constexpr (requires { x.next_node(); x.for_each([](decltype(x.next_node())) {}); }) {
                    using ElemT = std::decay_t<decltype(x.next_node())>;
                    std::vector<ElemT> v;
                    x.for_each([&](ElemT n) { v.push_back(std::move(n)); });
                    return v;
                } else if constexpr (requires { x->iterator(); }) {
                    auto it = x->iterator();
                    if constexpr (requires { it->next(); }) {
                        using ElemT = std::decay_t<decltype(it->next())>;
                        std::vector<ElemT> v;
                        while (it->has_next()) v.push_back(it->next());
                        return v;
                    } else {
                        using ElemT = std::decay_t<decltype(it.next())>;
                        std::vector<ElemT> v;
                        while (it.has_next()) v.push_back(it.next());
                        return v;
                    }
                } else if constexpr (requires { x.iterator(); }) {
                    auto it = x.iterator();
                    if constexpr (requires { it->next(); }) {
                        using ElemT = std::decay_t<decltype(it->next())>;
                        std::vector<ElemT> v;
                        while (it->has_next()) v.push_back(it->next());
                        return v;
                    } else {
                        using ElemT = std::decay_t<decltype(it.next())>;
                        std::vector<ElemT> v;
                        while (it.has_next()) v.push_back(it.next());
                        return v;
                    }
                } else {
                    return std::forward<T>(x);
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsStringBuilderDef) {
        out.append(
            """

            #ifndef KXS_STRING_BUILDER_DEFINED
            #define KXS_STRING_BUILDER_DEFINED
            struct KxsStringBuilder {
                std::string buf_{};

                KxsStringBuilder() = default;
                explicit KxsStringBuilder(std::string s) : buf_(std::move(s)) {}
                explicit KxsStringBuilder(std::int32_t capacity) {
                    if (capacity > 0) buf_.reserve(static_cast<std::size_t>(capacity));
                }

                KxsStringBuilder& append(const std::string& s) {
                    buf_.append(s);
                    return *this;
                }
                KxsStringBuilder& append(const char* s) {
                    if (s) buf_.append(s);
                    return *this;
                }
                KxsStringBuilder& append(char c) {
                    buf_.push_back(c);
                    return *this;
                }
                KxsStringBuilder& append(char16_t c) {
                    buf_.push_back(static_cast<char>(c));
                    return *this;
                }
                KxsStringBuilder& append(bool b) {
                    buf_.append(b ? "true" : "false");
                    return *this;
                }
                template <typename T>
                    requires (std::is_arithmetic_v<std::remove_cvref_t<T>> &&
                              !std::is_same_v<std::remove_cvref_t<T>, bool> &&
                              !std::is_same_v<std::remove_cvref_t<T>, char> &&
                              !std::is_same_v<std::remove_cvref_t<T>, char16_t>)
                KxsStringBuilder& append(T v) {
                    buf_.append(std::to_string(v));
                    return *this;
                }
                template <typename T>
                    requires (!std::is_arithmetic_v<std::remove_cvref_t<T>> &&
                              !std::is_convertible_v<T, std::string> &&
                              !std::is_convertible_v<T, const char*>)
                KxsStringBuilder& append(const T& v) {
                    if constexpr (requires { v->to_string(); }) {
                        if (v) buf_.append(v->to_string());
                        else buf_.append("null");
                    } else if constexpr (requires { v.to_string(); }) {
                        buf_.append(v.to_string());
                    }
                    return *this;
                }
                KxsStringBuilder& append_line() {
                    buf_.push_back('\n');
                    return *this;
                }
                template <typename T>
                KxsStringBuilder& append_line(T&& v) {
                    append(std::forward<T>(v));
                    buf_.push_back('\n');
                    return *this;
                }
                std::int32_t length() const noexcept { return static_cast<std::int32_t>(buf_.size()); }
                std::int32_t size() const noexcept { return static_cast<std::int32_t>(buf_.size()); }
                bool empty() const noexcept { return buf_.empty(); }
                char last() const { return buf_.empty() ? '\0' : buf_.back(); }
                KxsStringBuilder& delete_at(std::int32_t index) {
                    if (index >= 0 && static_cast<std::size_t>(index) < buf_.size()) {
                        buf_.erase(static_cast<std::size_t>(index), 1);
                    }
                    return *this;
                }
                void clear() noexcept { buf_.clear(); }
                std::string to_string() const { return buf_; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsEmptyContainersDef) {
        out.append(
            """

            #ifndef KXS_EMPTY_CONTAINERS_DEFINED
            #define KXS_EMPTY_CONTAINERS_DEFINED
            struct KxsEmptyList {
                const int* begin() const noexcept { return nullptr; }
                const int* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename T>
                operator std::vector<T>() const { return {}; }
            };
            struct KxsEmptySet {
                const int* begin() const noexcept { return nullptr; }
                const int* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename T>
                operator std::unordered_set<T>() const { return {}; }
            };
            struct KxsEmptyMap {
                const std::pair<const int, int>* begin() const noexcept { return nullptr; }
                const std::pair<const int, int>* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename K, typename V>
                operator std::unordered_map<K, V>() const { return {}; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
}
