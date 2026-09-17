/**
 * Os nomes canônicos de `java.time.DayOfWeek` — a única forma em que um dia da semana atravessa
 * qualquer fronteira do Spark (sync, backup, compartilhamento de programa). Rótulo de tela (`Seg`)
 * nunca é contrato (T19.8).
 *
 * Mora em `common/` porque `modules/backup` e `modules/social` usam o mesmo valor e não podem se
 * importar mutuamente (`object-storage-structure.spec.ts`).
 */
export const WEEKDAY_NAMES = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;

export type WeekdayName = (typeof WEEKDAY_NAMES)[number];

/** Uma lista de dias válida: só nomes canônicos, sem repetição, no máximo os sete. */
export function isCanonicalWeekdayList(value: unknown): value is WeekdayName[] {
  return (
    Array.isArray(value) &&
    value.length <= WEEKDAY_NAMES.length &&
    value.every((day) => (WEEKDAY_NAMES as readonly string[]).includes(day as string)) &&
    new Set(value).size === value.length
  );
}
