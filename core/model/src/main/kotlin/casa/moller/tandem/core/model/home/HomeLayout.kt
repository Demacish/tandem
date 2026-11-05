package casa.moller.tandem.core.model.home

/**
 * Fas 3's leftovers (plan §10 Fas 3 "Hemsektioner som användaren konfigurerar"): the order and
 * visibility of Today's recipe carousels, chosen by the user on this device (per account).
 *
 * @property order the user's order; types missing from it (a new section in a later version)
 *   follow in their default order ([types]).
 * @property hidden the sections the user turned off.
 */
data class HomeLayout(
    val order: List<HomeSectionType> = HomeSectionType.entries.toList(),
    val hidden: Set<HomeSectionType> = emptySet(),
) {
    /** Every section type in the user's order. */
    val types: List<HomeSectionType>
        get() = (order + HomeSectionType.entries).distinct()

    fun isVisible(type: HomeSectionType): Boolean = type !in hidden

    /** [sections] in the user's order, without the hidden ones. */
    fun arrange(sections: List<HomeSection>): List<HomeSection> {
        val byType = sections.associateBy { it.type }
        return types.filter { it !in hidden }.mapNotNull { byType[it] }
    }

    /** [type] moved [offset] places (negative = up), kept within the list. */
    fun move(type: HomeSectionType, offset: Int): HomeLayout {
        val list = types.toMutableList()
        val from = list.indexOf(type)
        val to = (from + offset).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return this
        list.removeAt(from)
        list.add(to, type)
        return copy(order = list)
    }

    fun withVisible(type: HomeSectionType, visible: Boolean): HomeLayout = copy(hidden = if (visible) hidden - type else hidden + type)

    /** As stored: `ORDER,…;HIDDEN,…` (enum names). */
    fun encode(): String = types.joinToString(",") { it.name } + ";" + hidden.sortedBy { it.ordinal }.joinToString(",") { it.name }

    companion object {
        /** A stored [encode]d layout; unknown names are skipped, anything unreadable is the default. */
        fun decode(text: String?): HomeLayout {
            if (text.isNullOrBlank()) return HomeLayout()
            val (order, hidden) = text.split(';').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            fun types(part: String) = part.split(',').mapNotNull { name -> HomeSectionType.entries.firstOrNull { it.name == name.trim() } }
            return HomeLayout(order = types(order).distinct().ifEmpty { HomeSectionType.entries.toList() }, hidden = types(hidden).toSet())
        }
    }
}
