package dev.tensorworkbench.api.web

data class PageRequest(val page: Int, val size: Int) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val MAX_SIZE = 100

        fun of(page: Int?, size: Int?): PageRequest {
            val p = page ?: 0
            val s = size ?: 20
            validate {
                require(p >= 0, "page") { "must be zero or greater" }
                require(s in 1..MAX_SIZE, "size") { "must be between 1 and $MAX_SIZE" }
            }
            return PageRequest(p, s)
        }
    }
}

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    val totalPages: Long get() = if (totalItems == 0L) 0 else (totalItems + size - 1) / size
}
