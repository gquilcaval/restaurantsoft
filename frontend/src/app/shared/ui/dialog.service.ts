import { Injectable, signal } from '@angular/core';

export type DialogTone = 'info' | 'warning' | 'danger' | 'success';

export interface DialogOptions {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: DialogTone;
  icon?: string;
}

export interface PromptDialogOptions extends DialogOptions {
  inputLabel: string;
  initialValue?: string;
  placeholder?: string;
  maxLength?: number;
  required?: boolean;
}

export type DialogRequest = DialogOptions & {
  kind: 'confirm' | 'prompt';
  inputLabel?: string;
  initialValue?: string;
  placeholder?: string;
  maxLength?: number;
  required?: boolean;
};

/** Sustituye los diálogos nativos por una experiencia visual uniforme y accesible. */
@Injectable({ providedIn: 'root' })
export class DialogService {
  readonly current = signal<DialogRequest | null>(null);
  private resolver?: (value: boolean | string | null) => void;

  confirm(options: DialogOptions): Promise<boolean> {
    return this.open({ ...options, kind: 'confirm' }) as Promise<boolean>;
  }

  prompt(options: PromptDialogOptions): Promise<string | null> {
    return this.open({ ...options, kind: 'prompt' }) as Promise<string | null>;
  }

  complete(value: boolean | string): void {
    const resolve = this.resolver;
    this.resolver = undefined;
    this.current.set(null);
    resolve?.(value);
  }

  cancel(): void {
    const request = this.current();
    const resolve = this.resolver;
    this.resolver = undefined;
    this.current.set(null);
    resolve?.(request?.kind === 'prompt' ? null : false);
  }

  private open(request: DialogRequest): Promise<boolean | string | null> {
    if (this.current()) this.cancel();
    this.current.set({
      confirmLabel: 'Confirmar', cancelLabel: 'Cancelar', tone: 'info', icon: '✓',
      ...request
    });
    return new Promise(resolve => this.resolver = resolve);
  }
}
