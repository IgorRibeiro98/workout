import type { AiOutputSchema } from '../ai-coach.output.schema';

/**
 * A tradução do schema de saída do Coach para o structured output **strict** da Groq (T19.H4 §20).
 *
 * O `AiOutputSchema` é escrito no vocabulário que a Gemini API entende (`OBJECT`, `nullable`,
 * `maxItems` como string) e continua sendo **o** schema do Coach — um só, para os dois providers.
 * Esta função é a única ponte para JSON Schema convencional, e ela vive na fronteira da Groq: o
 * schema de negócio não é reescrito, e nada aqui volta para o lado do Gemini.
 *
 * As regras do modo strict (constrained decoding) que motivam cada passo:
 *
 * - **todo campo de `properties` precisa estar em `required`** — um campo que o Spark declara
 *   opcional vira `required` + anulável, em vez de simplesmente ausente;
 * - **todo objeto é fechado** (`additionalProperties: false`);
 * - anulável é união de tipos (`["string", "null"]`), e um `enum` anulável precisa aceitar `null`
 *   na própria lista, senão o `null` que o tipo permite seria recusado pelo `enum`;
 * - `maxItems` é número, não string.
 *
 * Structured output garante **forma**. Não garante que o `exerciseId` exista, que a carga tenha
 * evidência ou que o substituto esteja entre os candidatos — isso continua com o `zod` e com o
 * validador semântico, que rodam depois, exatamente como rodam para o Gemini (§22).
 */

export type JsonSchema = Readonly<Record<string, unknown>>;

const JSON_TYPES: Readonly<Record<AiOutputSchema['type'], string>> = {
  OBJECT: 'object',
  ARRAY: 'array',
  STRING: 'string',
  NUMBER: 'number',
  INTEGER: 'integer',
  BOOLEAN: 'boolean',
};

export function toGroqStrictJsonSchema(schema: AiOutputSchema): JsonSchema {
  if (schema.type !== 'OBJECT') {
    // O structured output exige um objeto na raiz — e todo schema do Coach é um.
    throw new Error(`schema de saída precisa ter OBJECT na raiz, recebeu ${schema.type}`);
  }
  return convert(schema, false);
}

/** `forceNullable`: o campo era opcional no Spark e virou `required` só por exigência do strict. */
function convert(schema: AiOutputSchema, forceNullable: boolean): JsonSchema {
  const nullable = schema.nullable === true || forceNullable;
  const type = JSON_TYPES[schema.type];
  const out: Record<string, unknown> = { type: nullable ? [type, 'null'] : type };

  if (schema.description !== undefined) out.description = schema.description;
  if (schema.enum !== undefined) out.enum = nullable ? [...schema.enum, null] : [...schema.enum];
  if (schema.minimum !== undefined) out.minimum = schema.minimum;
  if (schema.maximum !== undefined) out.maximum = schema.maximum;

  if (schema.type === 'OBJECT') {
    const properties = schema.properties ?? {};
    const declaredRequired = new Set(schema.required ?? []);
    for (const name of declaredRequired) {
      if (!(name in properties)) {
        throw new Error(`required '${name}' não existe em properties`);
      }
    }
    out.properties = Object.fromEntries(
      Object.entries(properties).map(([name, property]) => [
        name,
        convert(property, !declaredRequired.has(name)),
      ]),
    );
    out.required = Object.keys(properties);
    out.additionalProperties = false;
  }

  if (schema.type === 'ARRAY') {
    if (!schema.items) throw new Error('ARRAY sem items');
    out.items = convert(schema.items, false);
    if (schema.maxItems !== undefined) out.maxItems = parseCount(schema.maxItems);
  }

  return out;
}

function parseCount(value: string): number {
  // O vocabulário do Gemini declara `maxItems` como string numérica ("5"). Qualquer outra coisa é
  // erro de quem escreveu o schema, e precisa aparecer no teste — não virar `NaN` na requisição.
  if (!/^\d+$/.test(value)) throw new Error(`maxItems inválido: '${value}'`);
  return Number(value);
}

/**
 * Desfaz, na resposta, o único efeito do strict que o contrato do Spark não conhece.
 *
 * Um campo que o Spark declara opcional e **não** anulável volta da Groq como `null` (o strict
 * obriga todo campo a aparecer). Para o contrato do Spark isso é um valor que ele nunca permitiu;
 * para o modelo, era só "não se aplica". Aqui o `null` vira ausência — exatamente o que o Gemini
 * devolveria —, e só nesses campos: um `null` em campo anulável é resposta legítima e fica.
 *
 * Não valida nada: tipos errados, campos extras e o resto seguem intactos para o `zod` recusar.
 */
export function dropStrictOnlyNulls(value: unknown, schema: AiOutputSchema): unknown {
  if (value === null || typeof value !== 'object') return value;

  if (schema.type === 'ARRAY') {
    if (!Array.isArray(value) || !schema.items) return value;
    const items = schema.items;
    return value.map((item) => dropStrictOnlyNulls(item, items));
  }

  if (schema.type !== 'OBJECT' || Array.isArray(value)) return value;

  const properties = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  const out: Record<string, unknown> = {};
  for (const [name, field] of Object.entries(value as Record<string, unknown>)) {
    const property = Object.prototype.hasOwnProperty.call(properties, name)
      ? properties[name]
      : undefined;
    if (!property) {
      out[name] = field;
      continue;
    }
    if (field === null && property.nullable !== true && !required.has(name)) {
      continue;
    }
    out[name] = dropStrictOnlyNulls(field, property);
  }
  return out;
}
