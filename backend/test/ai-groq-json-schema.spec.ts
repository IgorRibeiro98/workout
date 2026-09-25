import { responseSchemaFor, type AiOutputSchema } from '../src/modules/ai/ai-coach.output.schema';
import { AI_COACH_REQUEST_TYPES } from '../src/modules/ai/ai-coach.contract';
import {
  dropStrictOnlyNulls,
  toGroqStrictJsonSchema,
  type JsonSchema,
} from '../src/modules/ai/provider/groq-json-schema';

/**
 * A ponte `AiOutputSchema` (vocabulário do Gemini) → JSON Schema strict da Groq.
 *
 * As regras do strict não são negociáveis — um objeto aberto ou um campo fora de `required` faz a
 * Groq recusar a requisição inteira — e por isso cada uma é conferida em **todos** os schemas do
 * Coach, não num exemplo.
 */
describe('Schema do Coach → JSON Schema strict da Groq', () => {
  const schemas = AI_COACH_REQUEST_TYPES.map((type) => [type, responseSchemaFor(type)] as const);

  it.each(schemas)('%s: todo objeto é fechado e todo campo é required', (_type, schema) => {
    const converted = toGroqStrictJsonSchema(schema);

    for (const object of objectsIn(converted)) {
      expect(object.additionalProperties).toBe(false);
      expect([...(object.required as string[])].sort()).toEqual(
        Object.keys(object.properties as Record<string, unknown>).sort(),
      );
    }
  });

  it.each(schemas)(
    '%s: só vocabulário JSON Schema — nenhum tipo em maiúsculas, nenhum nullable',
    (_type, schema) => {
      const text = JSON.stringify(toGroqStrictJsonSchema(schema));

      expect(text).not.toMatch(/"(OBJECT|ARRAY|STRING|NUMBER|INTEGER|BOOLEAN)"/);
      expect(text).not.toContain('"nullable"');
      // maxItems vira número; string só existia no vocabulário do Gemini.
      expect(text).not.toMatch(/"maxItems":"/);
    },
  );

  it('converte cada tipo do Spark para o tipo JSON correspondente', () => {
    const schema: AiOutputSchema = {
      type: 'OBJECT',
      properties: {
        o: { type: 'OBJECT', properties: {}, required: [] },
        a: { type: 'ARRAY', items: { type: 'STRING' } },
        s: { type: 'STRING' },
        n: { type: 'NUMBER' },
        i: { type: 'INTEGER' },
        b: { type: 'BOOLEAN' },
      },
      required: ['o', 'a', 's', 'n', 'i', 'b'],
    };
    const properties = toGroqStrictJsonSchema(schema).properties as Record<string, JsonSchema>;

    expect(properties.o.type).toBe('object');
    expect(properties.a.type).toBe('array');
    expect((properties.a.items as JsonSchema).type).toBe('string');
    expect(properties.s.type).toBe('string');
    expect(properties.n.type).toBe('number');
    expect(properties.i.type).toBe('integer');
    expect(properties.b.type).toBe('boolean');
  });

  it('nullable vira união com null; opcional vira required + anulável', () => {
    const schema: AiOutputSchema = {
      type: 'OBJECT',
      properties: {
        obrigatorio: { type: 'STRING' },
        anulavel: { type: 'NUMBER', nullable: true },
        opcional: { type: 'INTEGER' },
        enumAnulavel: { type: 'STRING', enum: ['A', 'B'], nullable: true },
      },
      required: ['obrigatorio', 'anulavel', 'enumAnulavel'],
    };
    const converted = toGroqStrictJsonSchema(schema);
    const properties = converted.properties as Record<string, JsonSchema>;

    expect(properties.obrigatorio.type).toBe('string');
    expect(properties.anulavel.type).toEqual(['number', 'null']);
    // O Spark declara opcional; o strict exige presença — então aparece, podendo ser null.
    expect(properties.opcional.type).toEqual(['integer', 'null']);
    expect(converted.required).toEqual(['obrigatorio', 'anulavel', 'opcional', 'enumAnulavel']);
    // Sem null no enum, o null que o tipo permite seria recusado pelo próprio enum.
    expect(properties.enumAnulavel.enum).toEqual(['A', 'B', null]);
  });

  it('maxItems, minimum, maximum e description atravessam; maxItems como número', () => {
    const analysis = toGroqStrictJsonSchema(responseSchemaFor('ANALYZE_WORKOUT'));
    const recommendations = (analysis.properties as Record<string, JsonSchema>).recommendations;
    const confidence = (
      (recommendations.items as JsonSchema).properties as Record<string, JsonSchema>
    ).confidence;

    expect(recommendations.maxItems).toBe(5);
    expect(recommendations.description).toEqual(expect.any(String));
    expect(confidence).toMatchObject({ type: 'number', minimum: 0, maximum: 1 });
  });

  it('o schema de adaptação continua sem maxItems em changes (o teto vive na descrição)', () => {
    const adaptation = toGroqStrictJsonSchema(responseSchemaFor('ADAPT_WORKOUT'));
    const changes = (adaptation.properties as Record<string, JsonSchema>).changes;

    expect(changes).not.toHaveProperty('maxItems');
    expect(String(changes.description)).toContain('no máximo 12');
  });

  it('não altera o schema do Spark: a conversão é só na fronteira', () => {
    const original = responseSchemaFor('ADAPT_WORKOUT');
    const before = JSON.stringify(original);
    toGroqStrictJsonSchema(original);
    expect(JSON.stringify(original)).toBe(before);
    expect(before).toContain('"nullable":true');
  });

  it('schema malformado é erro de programação, não requisição com NaN', () => {
    expect(() =>
      toGroqStrictJsonSchema({
        type: 'OBJECT',
        properties: { a: { type: 'ARRAY', items: { type: 'STRING' }, maxItems: 'cinco' } },
      }),
    ).toThrow(/maxItems/);
    expect(() =>
      toGroqStrictJsonSchema({ type: 'OBJECT', properties: {}, required: ['fantasma'] }),
    ).toThrow(/fantasma/);
    expect(() => toGroqStrictJsonSchema({ type: 'ARRAY', items: { type: 'STRING' } })).toThrow(
      /OBJECT/,
    );
  });

  // ------------------------------------------------------------------ volta para o contrato

  it('dropStrictOnlyNulls remove null só de campo opcional não anulável, em qualquer profundidade', () => {
    const schema: AiOutputSchema = {
      type: 'OBJECT',
      properties: {
        titulo: { type: 'STRING' },
        apelido: { type: 'STRING' },
        nota: { type: 'NUMBER', nullable: true },
        itens: {
          type: 'ARRAY',
          items: {
            type: 'OBJECT',
            properties: { id: { type: 'STRING' }, extra: { type: 'STRING' } },
            required: ['id'],
          },
        },
      },
      required: ['titulo', 'itens'],
    };

    const restored = dropStrictOnlyNulls(
      {
        titulo: 'T',
        apelido: null,
        nota: null,
        itens: [
          { id: 'a', extra: null },
          { id: 'b', extra: 'x' },
        ],
      },
      schema,
    );

    expect(restored).toEqual({
      titulo: 'T',
      nota: null,
      itens: [{ id: 'a' }, { id: 'b', extra: 'x' }],
    });
  });

  it('dropStrictOnlyNulls não valida nem corrige: tipo errado e campo extra seguem para o zod', () => {
    const schema: AiOutputSchema = {
      type: 'OBJECT',
      properties: { titulo: { type: 'STRING' } },
      required: ['titulo'],
    };
    expect(dropStrictOnlyNulls({ titulo: 42, intruso: true }, schema)).toEqual({
      titulo: 42,
      intruso: true,
    });
    // null em campo required não anulável fica: é o zod que o recusa.
    expect(dropStrictOnlyNulls({ titulo: null }, schema)).toEqual({ titulo: null });
  });
});

function objectsIn(schema: JsonSchema): JsonSchema[] {
  const found: JsonSchema[] = [];
  const walk = (node: JsonSchema): void => {
    const type = node.type;
    const isObject = type === 'object' || (Array.isArray(type) && type.includes('object'));
    if (isObject) {
      found.push(node);
      for (const child of Object.values(node.properties as Record<string, JsonSchema>)) {
        walk(child);
      }
    }
    if (node.items) walk(node.items as JsonSchema);
  };
  walk(schema);
  return found;
}
