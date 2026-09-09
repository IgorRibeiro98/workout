import { Injectable } from '@nestjs/common';
import sharp from 'sharp';
import {
  ACCEPTED_IMAGE_FORMATS,
  MAX_DECODED_PIXELS,
  MAX_INPUT_EDGE_PX,
  MAX_OUTPUT_BYTES,
  MAX_OUTPUT_EDGE_PX,
  OUTPUT_MIME_TYPE,
  OUTPUT_QUALITY_STEPS,
  type AcceptedImageFormat,
} from './social-media.limits';

/** Por que uma imagem foi recusada. Classes, e não a mensagem da biblioteca (que vaza detalhe). */
export type ImageRejection =
  | 'UNSUPPORTED_FORMAT'
  | 'CORRUPT_IMAGE'
  | 'ANIMATED_NOT_SUPPORTED'
  | 'DIMENSIONS_TOO_LARGE'
  | 'OUTPUT_TOO_LARGE';

export class ImageProcessingError extends Error {
  constructor(readonly rejection: ImageRejection) {
    super(rejection);
    this.name = 'ImageProcessingError';
  }
}

/** A imagem como ela será armazenada — já sanitizada, redimensionada e re-encodada. */
export interface ProcessedImage {
  readonly bytes: Buffer;
  readonly mimeType: string;
  readonly width: number;
  readonly height: number;
  /** O formato que os **bytes recebidos** realmente eram. Para log técnico, nunca para o DTO. */
  readonly sourceFormat: AcceptedImageFormat;
}

/**
 * O pipeline de imagem do Spark (T17.9 §15/§16/§17).
 *
 * ```text
 * bytes recebidos
 *      │
 *      ▼  decode real (sharp/libvips) — o Content-Type do cliente não participa (§14)
 * formato + dimensões
 *      │
 *      ▼  validate: formato aceito, não animado, pixels e arestas bounded (§13/§20)
 *      │
 *      ▼  rotate() — aplica a orientação EXIF e depois a descarta
 *      │
 *      ▼  resize inside 1600px, sem ampliar (§18)
 *      │
 *      ▼  re-encode WebP; nenhum metadata é copiado (§16)
 *      │
 *      ▼  cabe em 1.5 MB? senão, próxima qualidade (§19)
 * imagem sanitizada
 * ```
 *
 * ## Por que decodificar de verdade (§14)
 *
 * `Content-Type: image/jpeg` é uma afirmação de quem enviou. Um arquivo que começa com os bytes
 * mágicos de JPEG e continua com um payload de outra coisa passaria por qualquer verificação de
 * cabeçalho — e ficaria guardado no servidor, servido de volta para os amigos da pessoa, com o
 * navegador de cada um deles fazendo a própria detecção de tipo. Aqui o que sai é sempre um WebP
 * que **este processo** produziu a partir de pixels que ele mesmo decodificou. Não existe caminho
 * em que os bytes recebidos sejam os bytes armazenados.
 *
 * ## Por que o metadata some (§16)
 *
 * `sharp` não copia metadata para a saída a menos que se peça `withMetadata()` — e este arquivo
 * nunca pede. O resultado é que GPS, modelo do aparelho, número de série da câmera, miniatura
 * embutida e data original **não existem** no arquivo publicado. Isso não é um detalhe de
 * qualidade: a foto de um treino tirada em casa carrega, no EXIF de um celular comum, a
 * coordenada da casa da pessoa. Publicá-la para "amigos" é publicá-la para quem quer que um dia
 * entre na lista de amigos.
 *
 * `rotate()` é chamado **antes** do resize de propósito: ele lê a tag de orientação e aplica a
 * rotação aos pixels. Sem ele, descartar o EXIF viraria uma foto deitada; com ele, a orientação
 * vira geometria real e a tag deixa de ser necessária.
 */
@Injectable()
export class SocialMediaProcessor {
  /**
   * Decodifica, valida e reencoda. Lança [ImageProcessingError] — nunca devolve algo duvidoso.
   *
   * `limitInputPixels` é aplicado na **construção** do pipeline, então o teto de pixels vale
   * durante a leitura do cabeçalho: uma bomba de descompressão é recusada antes de qualquer
   * alocação proporcional ao tamanho declarado (§20). Ler `metadata()` primeiro e só depois
   * decodificar seria a mesma proteção com uma janela a menos.
   */
  async process(input: Buffer): Promise<ProcessedImage> {
    const probe = sharp(input, { limitInputPixels: MAX_DECODED_PIXELS, failOn: 'error' });

    let metadata;
    try {
      metadata = await probe.metadata();
    } catch {
      // Bytes que não são imagem, imagem truncada, e a bomba de descompressão recusada pelo
      // `limitInputPixels` caem todas aqui. Distinguir "não é imagem" de "é grande demais" pela
      // mensagem da libvips seria depender do texto de uma biblioteca.
      throw new ImageProcessingError('CORRUPT_IMAGE');
    }

    // §3 — animação é recusada **antes** do formato, e a ordem é deliberada.
    //
    // `pages > 1` é como a libvips descreve GIF animado, WebP animado e APNG. Se o formato fosse
    // conferido primeiro, um GIF animado seria recusado como "formato não suportado" — verdade,
    // mas a menos útil das duas: quem escolheu um GIF na galeria precisa saber que o problema é a
    // animação, não a extensão. E pegar o primeiro quadro em silêncio seria pior que qualquer uma
    // das mensagens: publicaria uma imagem que não é a que a pessoa escolheu, e ela só descobriria
    // vendo o resultado no Feed de alguém.
    if ((metadata.pages ?? 1) > 1) {
      throw new ImageProcessingError('ANIMATED_NOT_SUPPORTED');
    }

    const format = metadata.format;
    if (!isAccepted(format)) {
      throw new ImageProcessingError('UNSUPPORTED_FORMAT');
    }

    const width = metadata.width ?? 0;
    const height = metadata.height ?? 0;
    if (width <= 0 || height <= 0) {
      throw new ImageProcessingError('CORRUPT_IMAGE');
    }
    // O teto por aresta existe além do teto de pixels: 20000×1000 tem 20 MP e ainda assim é uma
    // imagem que nenhum caminho legítimo produz.
    if (width > MAX_INPUT_EDGE_PX || height > MAX_INPUT_EDGE_PX) {
      throw new ImageProcessingError('DIMENSIONS_TOO_LARGE');
    }
    if (width * height > MAX_DECODED_PIXELS) {
      throw new ImageProcessingError('DIMENSIONS_TOO_LARGE');
    }

    for (const quality of OUTPUT_QUALITY_STEPS) {
      const encoded = await sharp(input, {
        limitInputPixels: MAX_DECODED_PIXELS,
        failOn: 'error',
      })
        // Orientação vira geometria; a tag some junto com o resto do metadata.
        .rotate()
        .resize({
          width: MAX_OUTPUT_EDGE_PX,
          height: MAX_OUTPUT_EDGE_PX,
          fit: 'inside',
          // §18 — a saída nunca é maior que a entrada. Ampliar produziria um arquivo maior sem
          // um pixel de informação a mais.
          withoutEnlargement: true,
        })
        // Sem `withMetadata()`: é a ausência desta chamada que garante §16. Não é um esquecimento
        // — é a linha que este comentário existe para proteger de alguém "corrigir".
        .webp({ quality, effort: 4 })
        .toBuffer({ resolveWithObject: true })
        .catch(() => {
          throw new ImageProcessingError('CORRUPT_IMAGE');
        });

      if (encoded.data.length <= MAX_OUTPUT_BYTES) {
        return {
          bytes: encoded.data,
          mimeType: OUTPUT_MIME_TYPE,
          width: encoded.info.width,
          height: encoded.info.height,
          sourceFormat: format,
        };
      }
    }

    // Nem na menor qualidade coube. Recusar é o desenho: guardar acima do teto faria o teto ser
    // uma sugestão, e o teto é o que impede uma conta de encher a partição sozinha.
    throw new ImageProcessingError('OUTPUT_TOO_LARGE');
  }
}

function isAccepted(format: string | undefined): format is AcceptedImageFormat {
  return (
    typeof format === 'string' && (ACCEPTED_IMAGE_FORMATS as readonly string[]).includes(format)
  );
}
