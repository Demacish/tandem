package casa.moller.tandem.core.database

/** Which of the two database files (plan 6.2 "Demoläge": demo data never mixes with a real account). */
enum class DatabaseKind(val fileName: String) {
    /** The database of real, logged-in accounts. */
    REAL(TandemDatabase.FILE_NAME),

    /** The demo mode's database, filled with sample data; cleared when demo mode ends. */
    DEMO(TandemDatabase.DEMO_FILE_NAME),
}
