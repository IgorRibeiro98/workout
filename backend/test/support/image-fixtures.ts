import sharp, { type Metadata } from 'sharp';

/**
 * Imagens de teste construídas em memória (T17.9 §167/§168).
 *
 * Nenhum arquivo binário versionado: as fixtures nascem aqui, e é isso que permite ao teste de
 * EXIF **provar** o que prova. Um `.jpg` commitado com GPS dentro seria uma coordenada real de
 * alguém no repositório, e ninguém conseguiria revisar o diff dele.
 */

/** Um JPEG válido, com as dimensões pedidas. */
export function jpeg(width = 800, height = 600): Promise<Buffer> {
  return sharp({
    create: { width, height, channels: 3, background: { r: 30, g: 120, b: 60 } },
  })
    .jpeg({ quality: 90 })
    .toBuffer();
}

export function png(width = 640, height = 480): Promise<Buffer> {
  return sharp({
    create: { width, height, channels: 4, background: { r: 10, g: 10, b: 200, alpha: 1 } },
  })
    .png()
    .toBuffer();
}

/** WebP **estático** — o único WebP que a T17.9 aceita (§13). */
export function webp(width = 500, height = 500): Promise<Buffer> {
  return sharp({
    create: { width, height, channels: 3, background: { r: 200, g: 40, b: 40 } },
  })
    .webp()
    .toBuffer();
}

/**
 * Um GIF **animado**, montado byte a byte (§3).
 *
 * GIF89a de 2×2 com dois quadros e a extensão NETSCAPE de repetição. Escrito à mão porque
 * `sharp` sintetiza imagens estáticas: ele lê multi-página, não cria. Sessenta bytes de estrutura
 * conhecida provam o que uma fixture binária versionada provaria, e podem ser revisados no diff.
 *
 * `pages = 2` é o que a libvips reporta, e é o que o processador recusa — **antes** de olhar o
 * formato, para que a mensagem fale de animação e não de extensão.
 */
export function animatedGif(): Buffer {
  const bytes: number[] = [];
  const push = (...values: number[]) => bytes.push(...values);

  push(0x47, 0x49, 0x46, 0x38, 0x39, 0x61); // "GIF89a"
  push(0x02, 0x00, 0x02, 0x00); // 2 x 2
  push(0xf0, 0x00, 0x00); // tabela global de cores, 2 entradas
  push(0x00, 0x00, 0x00, 0xff, 0xff, 0xff); // preto e branco

  // Extensão NETSCAPE 2.0 — é ela que declara a repetição infinita.
  push(0x21, 0xff, 0x0b);
  push(...Buffer.from('NETSCAPE2.0', 'ascii'));
  push(0x03, 0x01, 0x00, 0x00, 0x00);

  for (let frame = 0; frame < 2; frame += 1) {
    push(0x21, 0xf9, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00); // controle gráfico (delay 100 ms)
    push(0x2c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00); // descritor de imagem
    push(0x02, 0x02, 0x4c, 0x01, 0x00); // LZW: código mínimo 2, um bloco
  }

  push(0x3b); // trailer
  return Buffer.from(bytes);
}

/** Um GIF **estático**: formato não aceito, e a recusa é por formato (§13). */
export function staticGif(): Promise<Buffer> {
  return sharp({
    create: { width: 32, height: 32, channels: 3, background: { r: 5, g: 5, b: 5 } },
  })
    .gif()
    .toBuffer();
}

/**
 * Um JPEG **com metadata**, incluindo coordenada de GPS (§168).
 *
 * Este é o coração do teste de privacidade: o arquivo de entrada carrega uma localização, e o
 * arquivo armazenado precisa não carregar nenhuma. A foto de um treino tirada em casa leva, no
 * EXIF de um celular comum, a coordenada da casa da pessoa — e publicá-la para "amigos" é
 * publicá-la para quem quer que um dia entre nessa lista.
 */
export async function jpegWithExifGps(width = 400, height = 300): Promise<Buffer> {
  const base = await sharp({
    create: { width, height, channels: 3, background: { r: 90, g: 90, b: 90 } },
  })
    .jpeg()
    .toBuffer();

  return sharp(base)
    .withExif({
      IFD0: {
        Make: 'SparkPhone',
        Model: 'SparkPhone X200',
        Software: 'SparkCam 4.2',
        DateTime: '2026:09:08 07:31:14',
      },
      IFD2: {
        // -23.5613, -46.6560 — um ponto em São Paulo, escrito no formato racional do EXIF.
        GPSLatitudeRef: 'S',
        GPSLatitude: '23/1 33/1 4068/100',
        GPSLongitudeRef: 'W',
        GPSLongitude: '46/1 39/1 4416/100',
      },
    })
    .toBuffer();
}

/**
 * Um PNG de poucos bytes que declara uma **aresta** absurda (§20).
 *
 * 30000 × 8 tem só 240 mil pixels — abaixo do teto de 20 MP —, e ainda assim nenhum caminho
 * legítimo produz uma imagem assim. É o caso que o teto por aresta pega e o teto por pixels não.
 */
export function oversizedEdge(edge = 30_000): Promise<Buffer> {
  return sharp({
    create: { width: edge, height: 8, channels: 3, background: { r: 0, g: 0, b: 0 } },
  })
    .png({ compressionLevel: 9 })
    .toBuffer();
}

/**
 * A bomba de descompressão de verdade (§20): arquivo pequeno, contagem de pixels enorme.
 *
 * 6000 × 4000 são 24 MP — acima do teto de 20 MP — e comprime a alguns kilobytes por ser uma cor
 * sólida. Se o teto fosse só de bytes, este arquivo passaria e custaria quase 100 MB de RAM ao ser
 * decodificado.
 */
export function decompressionBomb(): Promise<Buffer> {
  return sharp({
    create: { width: 6_000, height: 4_000, channels: 3, background: { r: 0, g: 0, b: 0 } },
  })
    .png({ compressionLevel: 9 })
    .toBuffer();
}

/**
 * Uma foto "de verdade" para os testes de quota (§29/§30).
 *
 * Ruído gaussiano comprime mal, e é isso que se quer aqui: uma cor sólida de 900×900 vira alguns
 * kilobytes, e um teste de quota sobre kilobytes não mediria nada. Este produz algumas centenas
 * de KB depois do pipeline — a ordem de grandeza de uma foto real.
 */
export function noisyPhoto(edge = 900): Promise<Buffer> {
  return sharp({
    create: {
      width: edge,
      height: edge,
      channels: 3,
      background: { r: 0, g: 0, b: 0 },
      noise: { type: 'gaussian', mean: 128, sigma: 90 },
    },
  })
    .jpeg({ quality: 92 })
    .toBuffer();
}

/** Bytes que não são imagem nenhuma, com um cabeçalho que **parece** JPEG (§14). */
export function fakeJpeg(): Buffer {
  return Buffer.concat([
    Buffer.from([0xff, 0xd8, 0xff, 0xe0]),
    Buffer.from('isto nao e uma imagem, e o Content-Type mentiu', 'utf8'),
  ]);
}

/** Lê os metadados de um buffer — usado para provar ausência, não presença. */
export async function metadataOf(buffer: Buffer): Promise<Metadata> {
  return sharp(buffer).metadata();
}
