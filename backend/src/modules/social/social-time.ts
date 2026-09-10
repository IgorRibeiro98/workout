/**
 * As primitivas de fuso horário IANA do domínio social.
 *
 * ## Por que elas existem em um arquivo próprio
 *
 * A T17.2 escreveu "meia-noite local de uma data, em epoch millis" dentro de
 * `social-progress.source.ts`, porque a semana canônica era a única coisa que precisava disso. A
 * T17.3 precisa da **mesma** conversão para outra pergunta — os dias de calendário de um desafio —
 * e copiá-la seria criar duas implementações da parte mais fácil de errar de todo o social.
 *
 * As duas divergiriam no primeiro ajuste, e a divergência apareceria do pior jeito possível: o
 * perfil do amigo dizendo "3 treinos esta semana" e o desafio contando 4 dias ativos sobre as
 * mesmas sessões, porque um dos dois virou o dia uma hora antes do outro.
 *
 * ```text
 * social-time.ts                 ← uma implementação
 *      ├── canonicalWeekWindow   (T17.2, em social-progress.source.ts)
 *      └── challengeDayWindows   (T17.3, em challenge-progress.source.ts)
 * ```
 *
 * ## Sem biblioteca de datas
 *
 * O `Intl.DateTimeFormat` do Node carrega o banco de fusos do ICU, que o runtime já atualiza. Uma
 * dependência nova traria uma segunda tabela de fusos para manter, e o Spark Backend não tem
 * nenhuma hoje (`package.json`: nest, pg, firebase-admin, pino, zod). Isto **não** é
 * um parser de fuso escrito à mão — a proibição de §204: quem responde "que horas são em São
 * Paulo" é o ICU, e o que está aqui é só a aritmética de calendário em volta da resposta dele.
 */

export const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * O identificador é um fuso IANA que este runtime conhece?
 *
 * Validado contra o **próprio runtime**, e não contra uma lista mantida à mão: uma lista
 * envelheceria a cada revisão do banco de fusos, e o modo de falhar seria recusar um fuso que o
 * `Intl` sabe converter — ou, pior, aceitar um que ele não sabe.
 */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return true;
  } catch {
    return false;
  }
}

/**
 * A **data** local de um instante, escrita como `Date.UTC(ano, mês, dia)`.
 *
 * O valor devolvido não é o instante da meia-noite local: é a data local carregada num inteiro que
 * a aritmética de calendário pode manipular sem fuso. A conversão de volta para instante é
 * [localMidnightToInstant].
 */
export function localCalendarDate(instantMs: number, timeZone: string): number {
  const fields = localFields(instantMs, timeZone);
  return Date.UTC(fields.year, fields.month - 1, fields.day);
}

/**
 * A meia-noite local do dia [dateAsUtc], em epoch millis.
 *
 * Duas passagens, e não uma: o deslocamento do fuso depende do instante, e o instante é o que se
 * está procurando. A primeira passagem usa o deslocamento na data escrita como UTC; a segunda o
 * corrige com o deslocamento no instante estimado, que é o que resolve as fronteiras de horário de
 * verão. Mais de duas passagens não acrescentam nada: o deslocamento é constante dentro de cada
 * lado da transição.
 *
 * É esta função que faz "um dia" **não** ser 24 horas quando o relógio vira (§203). Uma soma de
 * `+86400000` produziria, na virada, um dia de 23 ou 25 horas contado como se fosse de 24 — e um
 * treino da noite anterior apareceria no dia seguinte.
 */
export function localMidnightToInstant(dateAsUtc: number, timeZone: string): number {
  const firstGuess = dateAsUtc - zoneOffsetMs(dateAsUtc, timeZone);
  return dateAsUtc - zoneOffsetMs(firstGuess, timeZone);
}

/**
 * Quanto o relógio de parede de [timeZone] está adiantado em relação ao UTC, naquele instante.
 *
 * Medido comparando o instante com o mesmo relógio de parede escrito como se fosse UTC. É a forma
 * portátil de obter deslocamento com horário de verão sem depender de biblioteca externa.
 */
export function zoneOffsetMs(instantMs: number, timeZone: string): number {
  const fields = localFields(instantMs, timeZone);
  const wallClockAsUtc = Date.UTC(
    fields.year,
    fields.month - 1,
    fields.day,
    fields.hour,
    fields.minute,
    fields.second,
  );
  return wallClockAsUtc - instantMs;
}

export interface LocalFields {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

/** Os campos de relógio de parede de um instante, no fuso pedido. */
export function localFields(instantMs: number, timeZone: string): LocalFields {
  let formatter = formatterCache.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatterCache.set(timeZone, formatter);
  }

  const parts = new Map(
    formatter.formatToParts(new Date(instantMs)).map((part) => [part.type, part.value]),
  );

  return {
    year: Number(parts.get('year')),
    month: Number(parts.get('month')),
    day: Number(parts.get('day')),
    // `h23` já produz `00` à meia-noite; a normalização é a defesa contra um ICU que devolva `24`.
    hour: Number(parts.get('hour')) % 24,
    minute: Number(parts.get('minute')),
    second: Number(parts.get('second')),
  };
}

/** Uma data de calendário `YYYY-MM-DD`, sem fuso e sem hora. */
export interface CalendarDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * `YYYY-MM-DD` → [CalendarDate], ou `null` quando a data não existe.
 *
 * A verificação de existência é a volta pelo `Date.UTC`: `2026-02-30` tem forma válida e **não é
 * um dia**. Sem ela, o `Date` normalizaria para 2 de março em silêncio, e um desafio começaria
 * dois dias depois do que a pessoa escolheu.
 *
 * Nenhum fuso participa daqui. Uma data de calendário é a data que a pessoa escolheu na tela; o
 * instante em que ela começa depende do fuso do desafio, e essa conversão é [localMidnightToInstant].
 */
export function parseCalendarDate(raw: string): CalendarDate | null {
  const match = CALENDAR_DATE_PATTERN.exec(raw);
  if (!match) {
    return null;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);

  const asUtc = Date.UTC(year, month - 1, day);
  const roundTrip = new Date(asUtc);
  if (
    roundTrip.getUTCFullYear() !== year ||
    roundTrip.getUTCMonth() !== month - 1 ||
    roundTrip.getUTCDate() !== day
  ) {
    return null;
  }
  return { year, month, day };
}

/** [CalendarDate] → o inteiro `Date.UTC(...)` que a aritmética de calendário usa. */
export function calendarDateAsUtc(date: CalendarDate): number {
  return Date.UTC(date.year, date.month - 1, date.day);
}

/** O inteiro `Date.UTC(...)` de volta para `YYYY-MM-DD`. */
export function formatCalendarDate(dateAsUtc: number): string {
  return new Date(dateAsUtc).toISOString().slice(0, 10);
}

/**
 * A data de calendário **local** de um instante, em `YYYY-MM-DD`.
 *
 * É por aqui que "hoje, para este desafio" é respondido: o servidor guarda instantes UTC, e "hoje"
 * só existe dentro de um fuso.
 */
export function localCalendarDateString(instantMs: number, timeZone: string): string {
  return formatCalendarDate(localCalendarDate(instantMs, timeZone));
}
