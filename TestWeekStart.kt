import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

fun main() {
    val sunday = LocalDate.of(2023, 10, 8) // Sunday
    println("Sunday with MONDAY: ${sunday.with(DayOfWeek.MONDAY)}")
    println("Sunday previousOrSame MONDAY: ${sunday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}")
    
    val wednesday = LocalDate.of(2023, 10, 4) // Wednesday
    println("Wednesday with MONDAY: ${wednesday.with(DayOfWeek.MONDAY)}")
    println("Wednesday previousOrSame MONDAY: ${wednesday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}")
}
