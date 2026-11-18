import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

const MAX_SIDE = 1600;

/**
 * Sube fotos de productos y logotipos. Las fotos del celular se reducen en el navegador antes de enviarse,
 * así cargan rápido en la carta y no saturan el servidor.
 */
@Injectable({ providedIn: 'root' })
export class ImageUploadService {
  private readonly http = inject(HttpClient);

  async upload(file: File): Promise<string> {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      throw new Error('Solo se aceptan imágenes JPG, PNG o WebP.');
    }
    const body = new FormData();
    body.append('file', await this.compress(file), this.fileName(file));
    const response = await firstValueFrom(this.http.post<{ url: string }>('/api/admin/uploads/images', body));
    return response.url;
  }

  /** Mantiene PNG para conservar transparencias (logotipos); el resto se convierte a JPEG. */
  private async compress(file: File): Promise<Blob> {
    const bitmap = await createImageBitmap(file).catch(() => null);
    if (!bitmap) return file;
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size < 600_000) { bitmap.close(); return file; }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const type = file.type === 'image/png' ? 'image/png' : 'image/jpeg';
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, type, 0.85));
    return blob || file;
  }

  private fileName(file: File): string {
    return file.type === 'image/png' ? 'imagen.png' : 'imagen.jpg';
  }
}
