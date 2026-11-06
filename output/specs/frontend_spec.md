Entrego la especificación y el código Angular. **Nada se compiló ni se ejecutó** (`ng build`, `ng test` y pruebas manuales: NO EJECUTADO).

````markdown
// frontend_spec.md

# frontend_spec.md — Historia: Confirmación de pagos de bajo monto (#17, con consumo de #18)

> **Alcance y honestidad.** El contrato de Arquitectura recibido está truncado (sin rutas, códigos ni formato de errores). Para las rutas y códigos uso la tabla de endpoints y los códigos 400/404/409/503/500 del `backend_spec.md`. Los nombres de campos de request y response **no aparecen en ningún documento recibido**. Los deduje del modelo de dominio (`Payment`, `Destination`, `PaymentPreview`) y los marco **SUPUESTO PENDIENTE**. Todos están aislados en `payment.models.ts` y en `PaymentApiService`, para corregirlos en un solo lugar.
> No recibí código del frontend existente. Se asume Angular ≥ 17 (componentes standalone, signals, control flow `@if/@switch/@for`) y que `provideHttpClient()` está configurado en la app.
> **No se compiló ni se ejecutó nada: NO EJECUTADO.** No se recibieron observaciones de revisión previas.

## 1. Pantalla y recorrido

Componente `PaymentFlowComponent` (`app-payment-flow`) con un recorrido de pasos. Cada paso mueve el foco a su encabezado `h2`.

1. **form**: el pagador ingresa destino y monto → botón "Continuar" (llama a `POST /payments/preview`).
2. **confirm**: se muestran identidad del destino y monto devueltos por el servidor → "Confirmar pago" (`POST /payments` con `Idempotency-Key`) o "Editar datos".
3. **processing**: polling a `GET /payments/{id}` hasta estado terminal, sin acciones que dupliquen el cobro.
4. **delayed**: pasaron 60 s (valor del cliente, ver supuestos) o falló la consulta, y el pago puede seguir en curso. Solo ofrece "Consultar de nuevo" y "Ver historial".
5. **result**: un único resultado terminal.
   - `SUCCEEDED`: referencia de transacción y acciones "Ver historial" y "Hacer otro pago".
   - `FAILED` o `EXPIRED`: acciones "Reintentar" y "Volver".

`PaymentHistoryComponent` lista el historial (`GET /payments/history?page&size`) con estados de carga, vacío, error y paginación.

## 2. Campos y validaciones visibles

| Campo | Control | Validaciones del cliente (solo apoyo de UX) | Mensaje visible |
|---|---|---|---|
| Destino (identificador) | `destinationIdentifier` | requerido; no solo espacios | "Ingresa el destino del pago." |
| Monto | `amount` (texto decimal, `inputmode="decimal"`) | requerido; formato numérico con hasta N decimales (N = decimales de la moneda vía `Intl`); mayor que cero | "Ingresa el monto." / "Usa solo números con hasta N decimales. Ejemplo: 12.50" / "El monto debe ser mayor que cero." |
| Servidor (400 con `fieldErrors`) | marca el control | no es regla del cliente | "El monto no está dentro de los límites permitidos para este pago." / "Revisa este dato: no fue aceptado." |

Los datos `providerId`, `channel`, `currency` y `destinationType` **no son campos del formulario**. Llegan por `@Input() context` (SUPUESTO PENDIENTE: origen). **Los límites mínimo y máximo no se validan en el cliente.** No están en el contrato y los decide el servidor (`PaymentLimitsPort`), que responde 400.

## 3. Acciones del usuario

| Acción | Efecto | Protección |
|---|---|---|
| Continuar | Preview | Deshabilitado y con texto "Verificando…" mientras `busy()` |
| Confirmar pago | Crea el intento con `Idempotency-Key` | Deshabilitado mientras `busy()`, más guarda en código. La clave se genera una vez por intento y se **reutiliza** si hubo error de red o 5xx (no duplica el cobro) |
| Editar datos | Vuelve al formulario y descarta la clave | Deshabilitado mientras `busy()` |
| Consultar de nuevo | Reanuda el polling del mismo pago | No crea pagos |
| Reintentar (FAILED/EXPIRED) | Vuelve a "confirm" con **clave nueva** (nuevo intento, D5 de Arquitectura) | Solo visible en estado terminal |
| Volver | Reinicia el recorrido | Solo en estado terminal |
| Ver historial | Emite `viewHistory` | — |

## 4. Estados de pantalla

| Estado | Formulario/Confirmación | Resultado | Historial |
|---|---|---|---|
| Carga | Botón deshabilitado, texto de progreso, `aria-busy`, región `role="status"` | Indicador "Confirmando tu pago…" | "Cargando historial…" |
| Vacío | No aplica (no hay lista). Si el preview no devolviera datos, se trata como error | No aplica | "Aún no tienes pagos en tu historial." |
| Error | Caja `role="alert"` con mensaje traducido, foco en la caja, datos conservados | Paso `delayed` si falla la consulta | Alerta y botón "Reintentar carga" |
| Éxito/terminal | — | Etiqueta provisional + referencia (si `SUCCEEDED`) | Tabla |

Las etiquetas provisionales de estados terminales están en `STATUS_LABELS`. **SUPUESTO PENDIENTE: Producto debe definirlas.**

## 5. Mapeo de errores de API → mensaje (`payment-error.mapper.ts`)

Nunca se muestra `message`, `code` ni detalle técnico del servidor. Se traduce solo por estado HTTP y operación.

| Estado | Operación | Mensaje al usuario |
|---|---|---|
| 0 / red | cualquiera | "No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo." (en create añade que reintentar no duplica el cobro) |
| 400 | preview/create | "Revisa los datos ingresados. El monto debe estar dentro de los límites permitidos para este canal." + marca de campos si hay `fieldErrors` |
| 401 | cualquiera | "Tu sesión ya no es válida. Inicia sesión nuevamente." + evento `sessionExpired` |
| 403 | cualquiera | "No tienes permiso para realizar esta operación." |
| 404 | preview/create | "No encontramos el destino solicitado." |
| 404 | poll | "No encontramos este pago." |
| 409 | create | "Ya hay un pago en curso o esta operación ya fue registrada. Espera su resultado para no duplicar el cobro." |
| 503 | cualquiera | "El servicio de pagos no está disponible por ahora. Inténtalo en unos minutos." (create: añade que no se duplicará el cobro al reintentar) |
| 500 / otros 5xx | cualquiera | "Ocurrió un problema inesperado. Inténtalo nuevamente." |
| cualquiera | poll | "No pudimos consultar el estado de tu pago. Puede seguir en proceso: no lo repitas, vuelve a consultar." |

El `fieldErrors` del 400 está en el backend como "400 con `fieldErrors`", pero **su forma exacta no se recibió**. El mapper acepta arreglo de `{field}`/strings o un objeto con claves, y solo usa los nombres de campo. **SUPUESTO PENDIENTE (Backend).** El `409` no distingue "pago en curso" de "conflicto de idempotencia" porque el contrato recibido no trae un código; por eso el mensaje es unificado.

## 6. Contrato consumido

| Uso | Ruta (SUPUESTO de ruta, según `backend_spec.md`) | Notas |
|---|---|---|
| Preview | `POST /payments/preview` | Body `PaymentRequest` (SUPUESTO de campos) |
| Crear | `POST /payments` + header `Idempotency-Key` | 201 nuevo / 200 repetición; el cliente trata ambos igual |
| Consultar | `GET /payments/{id}` | Estados `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`. Un estado desconocido se trata como no terminal |
| Historial | `GET /payments/history?page&size` | Forma de `HistoryPageResponse` SUPUESTO |
| Comprobante | `GET /payments/{id}/receipt` | **No se consume en esta entrega** (formato no recibido) |

## 7. Accesibilidad

- Cada campo tiene `<label for>`; las pistas y errores se asocian con `aria-describedby`; `aria-invalid` se activa solo si el campo se tocó y tiene error.
- Los errores de API usan `role="alert"` y se enfocan (`tabindex="-1"`); el progreso usa `role="status"` con `aria-live="polite"`; `aria-busy` marca el contenedor ocupado.
- Gestión del foco: cada cambio de paso enfoca el `h2`; si el formulario es inválido, se enfoca el primer campo inválido.
- Todo es operable por teclado (elementos nativos `button`, `input`, `form`; Enter envía). `:focus-visible` explícito.
- El contraste de los colores del SCSS fue elegido para superar 4.5:1 según cálculo estimado; **no medido con herramienta: NO EJECUTADO**.
- El estado no se comunica solo por color: hay texto y encabezado propios por resultado.
- Los datos del usuario y del servidor se renderizan con interpolación; no hay `innerHTML`.

## 8. Seguridad

- La validación del cliente no sustituye la del servidor. Límites, autorización y duplicados los decide el backend.
- No se guardan tokens ni claves en `localStorage`/`sessionStorage`. La `Idempotency-Key` vive solo en memoria del componente. La autenticación se asume resuelta por la app (interceptor/cookie), fuera de este alcance.
- Sin secretos en el código. `PAYMENTS_API_BASE` es configurable por inyección.

## 9. Trazabilidad con criterios de aceptación y evidencia

| CA | Archivo / elemento | Verificación propuesta (NO EJECUTADO) |
|---|---|---|
| 1. Destino y monto antes de confirmar | `payment-flow.component.html`, bloque `@case ('confirm')`, `dl` con `displayName`, identificador y monto del `PreviewResponse` | Manual: completar el formulario y comprobar que, antes de pulsar "Confirmar pago", se ve el destino y el monto devueltos por el servidor. Backend simulado o real |
| 2. Un único resultado terminal | `payment-api.service.ts` `poll()` (`takeWhile` inclusivo) y `payment-flow.component.ts` `startPolling()`/`result` | `ng test`: `payment-api.service.spec.ts` ("poll se detiene en el primer estado terminal"). Manual: con respuestas PENDING→SUCCEEDED, una sola pantalla de resultado |
| 3. Referencia y historial | Resultado: `html` bloque `SUCCEEDED` (`transactionReference`). Historial: `payment-history.component.*` | Manual: tras un pago exitoso, ver la referencia y luego el pago en el historial. **La persistencia la garantiza el backend** |
| 4. Siguiente acción sin duplicados | `html` botones Reintentar/Volver; `component.ts` `busy()`, guarda en `confirm()`, `idempotencyKey` (misma clave en errores ambiguos, clave nueva en `retry()`) | `ng test`: header `Idempotency-Key` en `create`. Manual: doble clic rápido en "Confirmar" genera una sola petición (pestaña Network); en FAILED/EXPIRED aparecen ambas acciones |
| 5. Caída en checkout 30%→8% | **No verificable en frontend.** Es métrica de negocio/analítica | Requiere medición en producción (Producto/Datos) |
| 6. Evento de activación (MAU) | **Responsabilidad del backend** (`PaymentEventPublisherPort`). El frontend no emite este evento | Verificar en backend. Si Producto quiere eventos de funnel desde el cliente, **SUPUESTO PENDIENTE** (no existe contrato) |

Comandos propuestos: `ng test --watch=false` y `ng build`. **Resultado: NO EJECUTADO.** Hay specs escritas para el mapper de errores y el servicio. **No escribí spec del componente**: su verificación queda como pasos manuales de la tabla.

## 10. Supuestos pendientes

| # | Supuesto | Quién define |
|---|---|---|
| S1 | Rutas exactas y prefijo de API (`PAYMENTS_API_BASE`), según el `backend_spec.md` | Arquitectura/Backend |
| S2 | Nombres y estructura de `PaymentRequest`, `PreviewResponse`, `PaymentResponse`, `HistoryPageResponse` y `ErrorResponse` (incluye forma de `fieldErrors`) | Backend |
| S3 | Origen de `providerId`, `channel`, `currency`, `destinationType` (`context`) y tipos de destino admitidos | Producto/Arquitectura |
| S4 | Etiquetas visibles de estados terminales y textos finales | Producto |
| S5 | Intervalo (2 s) y tope (60 s) de polling del cliente | Producto/Backend |
| S6 | Si la API distingue "pago en curso" de "conflicto de idempotencia" (códigos en 409) | Backend |
| S7 | Enmascarar o no el identificador del destino en pantalla | Seguridad/Producto |
| S8 | Autenticación (interceptor/cookie), `withCredentials`, ruta de login tras 401 (se emite `sessionExpired`) | Arquitectura |
| S9 | Pantalla de comprobante (`/receipt`) y navegación al historial | Producto/Backend |
| S10 | Eventos de funnel desde el cliente | Producto/Datos |
| S11 | Decimales de la moneda: se derivan de `Intl` y asumen que el backend usa la misma unidad menor (`amountMinor`) | Backend/Datos |

## 11. Correcciones

Sin observaciones previas recibidas; no hay correcciones que reportar.
````

```typescript
// payment.models.ts

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
export type TerminalStatus = Exclude<PaymentStatus, 'PENDING'>;

const TERMINALS: readonly string[] = ['SUCCEEDED', 'FAILED', 'EXPIRED'];

/** Un estado desconocido NO se considera terminal. */
export function isTerminal(status: string | null | undefined): status is TerminalStatus {
  return !!status && TERMINALS.includes(status);
}

/** SUPUESTO PENDIENTE (Producto): etiquetas provisionales. La API devuelve solo códigos. */
export const STATUS_LABELS: Record<TerminalStatus, string> = {
  SUCCEEDED: 'Pago exitoso',
  FAILED: 'Pago fallido',
  EXPIRED: 'Pago expirado',
};

export const STATUS_MESSAGES: Record<TerminalStatus, string> = {
  SUCCEEDED: 'Tu pago se realizó correctamente.',
  FAILED: 'No pudimos completar tu pago. No se realizó el cobro. Puedes intentarlo de nuevo o volver.',
  EXPIRED: 'El tiempo para completar tu pago terminó. Puedes intentarlo de nuevo o volver.',
};

// ---- SUPUESTO PENDIENTE (Backend): nombres de campos deducidos del modelo de dominio ----

export interface DestinationDto {
  type: string;
  identifier: string;
  displayName: string;
}

/** Datos de contexto que NO captura el usuario. SUPUESTO PENDIENTE: origen (S3). */
export interface PaymentContext {
  providerId: string;
  channel: string;
  currency: string;
  destinationType: string;
}

export interface PaymentRequest {
  providerId: string;
  channel: string;
  destination: { type: string; identifier: string };
  amountMinor: number;
  currency: string;
}

export interface PreviewResponse {
  providerId: string;
  channel: string;
  destination: DestinationDto;
  amountMinor: number;
  currency: string;
}

export interface PaymentResponse {
  id: string;
  status: PaymentStatus;
  transactionReference?: string | null;
  destination?: DestinationDto;
  amountMinor?: number;
  currency?: string;
  createdAt?: string;
  completedAt?: string | null;
}

export interface HistoryPageResponse {
  items: PaymentResponse[];
  page: number;
  size: number;
  totalElements?: number;
}

/** Solo para tipar el cuerpo de error; el texto del servidor NUNCA se muestra. */
export interface ErrorResponse {
  code?: string;
  message?: string;
  fieldErrors?: unknown;
}
```

```typescript
// money.util.ts

/** Decimales de la moneda según Intl (SUPUESTO S11: coincide con la unidad menor del backend). */
export function currencyExponent(currency: string): number {
  try {
    return (
      new Intl.NumberFormat('es', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2
    );
  } catch {
    return 2;
  }
}

/** Convierte "12.50" o "12,50" a unidades menores sin usar punto flotante. null si el formato es inválido. */
export function parseAmountToMinor(text: string, exponent: number): number | null {
  const t = (text ?? '').trim().replace(',', '.');
  const re = exponent === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${exponent}})?$`);
  if (!re.test(t)) {
    return null;
  }
  const [int, frac = ''] = t.split('.');
  const n = Number(int + frac.padEnd(exponent, '0'));
  return Number.isSafeInteger(n) ? n : null;
}

export function formatMinor(amountMinor: number, currency: string): string {
  const exp = currencyExponent(currency);
  const value = amountMinor / Math.pow(10, exp);
  try {
    return new Intl.NumberFormat('es', { style: 'currency', currency }).format(value);
  } catch {
    return `${value.toFixed(exp)} ${currency}`;
  }
}
```

```typescript
// payment-error.mapper.ts
import { HttpErrorResponse } from '@angular/common/http';

export type ApiOperation = 'preview' | 'create' | 'poll' | 'history';

export interface UserError {
  /** Mensaje comprensible; nunca incluye texto técnico ni del servidor. */
  message: string;
  /** Nombres de campos señalados por el servidor (solo nombres, no textos). */
  fieldNames: string[];
  sessionExpired: boolean;
  /** true si el resultado de create es ambiguo y se debe reutilizar la Idempotency-Key. */
  keepIdempotencyKey: boolean;
}

const NO_DUPLICATE = ' Si vuelves a intentar, no se duplicará el cobro.';

function extractFieldNames(body: unknown): string[] {
  const fe = (body as { fieldErrors?: unknown } | null)?.fieldErrors;
  if (Array.isArray(fe)) {
    return fe
      .map((i) => (typeof i === 'string' ? i : (i as { field?: unknown })?.field))
      .filter((n): n is string => typeof n === 'string');
  }
  if (fe && typeof fe === 'object') {
    return Object.keys(fe as Record<string, unknown>);
  }
  return [];
}

export function toUserError(err: unknown, op: ApiOperation): UserError {
  const base: UserError = {
    message: 'Ocurrió un problema inesperado. Inténtalo nuevamente.',
    fieldNames: [],
    sessionExpired: false,
    keepIdempotencyKey: false,
  };

  if (!(err instanceof HttpErrorResponse)) {
    return base;
  }

  const status = err.status;
  const ambiguousCreate = op === 'create';

  if (op === 'poll' && status !== 401 && status !== 403) {
    return {
      ...base,
      message:
        'No pudimos consultar el estado de tu pago. Puede seguir en proceso: no lo repitas, vuelve a consultar.',
    };
  }

  if (status === 0) {
    return {
      ...base,
      message:
        'No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo.' +
        (ambiguousCreate ? NO_DUPLICATE : ''),
      keepIdempotencyKey: ambiguousCreate,
    };
  }

  switch (status) {
    case 400:
      return {
        ...base,
        message:
          'Revisa los datos ingresados. El monto debe estar dentro de los límites permitidos para este canal.',
        fieldNames: extractFieldNames(err.error),
      };
    case 401:
      return {
        ...base,
        message: 'Tu sesión ya no es válida. Inicia sesión nuevamente.',
        sessionExpired: true,
      };
    case 403:
      return { ...base, message: 'No tienes permiso para realizar esta operación.' };
    case 404:
      return {
        ...base,
        message:
          op === 'poll' ? 'No encontramos este pago.' : 'No encontramos el destino solicitado.',
      };
    case 409:
      return {
        ...base,
        message:
          'Ya hay un pago en curso o esta operación ya fue registrada. Espera su resultado para no duplicar el cobro.',
        keepIdempotencyKey: ambiguousCreate,
      };
    case 503:
      return {
        ...base,
        message:
          'El servicio de pagos no está disponible por ahora. Inténtalo en unos minutos.' +
          (ambiguousCreate ? NO_DUPLICATE : ''),
        keepIdempotencyKey: ambiguousCreate,
      };
    default:
      if (status >= 500) {
        return {
          ...base,
          message:
            'Ocurrió un problema inesperado. Inténtalo nuevamente.' +
            (ambiguousCreate ? NO_DUPLICATE : ''),
          keepIdempotencyKey: ambiguousCreate,
        };
      }
      return base;
  }
}
```

```typescript
// payment-api.service.ts
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, exhaustMap, takeUntil, takeWhile, timer } from 'rxjs';
import {
  HistoryPageResponse,
  PaymentRequest,
  PaymentResponse,
  PreviewResponse,
  isTerminal,
} from './payment.models';

/** SUPUESTO PENDIENTE (S1): prefijo de la API. */
export const PAYMENTS_API_BASE = new InjectionToken<string>('PAYMENTS_API_BASE', {
  providedIn: 'root',
  factory: () => '',
});

/** SUPUESTO PENDIENTE (S5): valores del cliente, a confirmar con Producto/Backend. */
export const POLL_INTERVAL_MS = 2000;
export const POLL_MAX_MS = 60000;

@Injectable({ providedIn: 'root' })
export class PaymentApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(PAYMENTS_API_BASE);

  preview(req: PaymentRequest): Observable<PreviewResponse> {
    return this.http.post<PreviewResponse>(`${this.base}/payments/preview`, req);
  }

  create(req: PaymentRequest, idempotencyKey: string): Observable<PaymentResponse> {
    return this.http.post<PaymentResponse>(`${this.base}/payments`, req, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  get(id: string): Observable<PaymentResponse> {
    return this.http.get<PaymentResponse>(`${this.base}/payments/${encodeURIComponent(id)}`);
  }

  /**
   * Consulta periódica hasta el primer estado terminal (se emite y se completa).
   * Si se alcanza el tope de tiempo sin terminal, completa sin emitir terminal:
   * el llamador debe tratarlo como "resultado aún no disponible", nunca como fallo.
   */
  poll(id: string): Observable<PaymentResponse> {
    return timer(0, POLL_INTERVAL_MS).pipe(
      exhaustMap(() => this.get(id)),
      takeWhile((p) => !isTerminal(p.status), true),
      takeUntil(timer(POLL_MAX_MS)),
    );
  }

  history(page: number, size: number): Observable<HistoryPageResponse> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<HistoryPageResponse>(`${this.base}/payments/history`, { params });
  }
}
```

```typescript
// payment-flow.component.ts
import { Component, ElementRef, EventEmitter, Input, OnDestroy, OnInit, Output, ViewChild, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Subscription, finalize } from 'rxjs';
import { PaymentApiService } from './payment-api.service';
import { UserError, toUserError } from './payment-error.mapper';
import { currencyExponent, formatMinor, parseAmountToMinor } from './money.util';
import {
  PaymentContext,
  PaymentRequest,
  PaymentResponse,
  PreviewResponse,
  STATUS_LABELS,
  STATUS_MESSAGES,
  TerminalStatus,
  isTerminal,
} from './payment.models';

type Step = 'form' | 'confirm' | 'processing' | 'delayed' | 'result';

@Component({
  selector: 'app-payment-flow',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './payment-flow.component.html',
  styleUrl: './payment-flow.component.scss',
})
export class PaymentFlowComponent implements OnInit, OnDestroy {
  /** SUPUESTO PENDIENTE (S3): origen del contexto. */
  @Input({ required: true }) context!: PaymentContext;
  @Output() viewHistory = new EventEmitter<void>();
  @Output() sessionExpired = new EventEmitter<void>();

  @ViewChild('stepHeading') private heading?: ElementRef<HTMLElement>;
  @ViewChild('errorBox') private errorBox?: ElementRef<HTMLElement>;

  private readonly api = inject(PaymentApiService);
  private readonly fb = inject(FormBuilder);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly step = signal<Step>('form');
  readonly busy = signal(false);
  readonly error = signal<UserError | null>(null);
  readonly preview = signal<PreviewResponse | null>(null);
  readonly payment = signal<PaymentResponse | null>(null);

  readonly terminalStatus = computed<TerminalStatus | null>(() => {
    const s = this.payment()?.status;
    return isTerminal(s) ? s : null;
  });
  readonly resultLabel = computed(() => {
    const s = this.terminalStatus();
    return s ? STATUS_LABELS[s] : '';
  });
  readonly resultMessage = computed(() => {
    const s = this.terminalStatus();
    return s ? STATUS_MESSAGES[s] : '';
  });
  readonly liveMessage = computed(() => {
    if (this.step() === 'form' && this.busy()) return 'Verificando monto y destino…';
    if (this.step() === 'confirm' && this.busy()) return 'Enviando el pago…';
    if (this.step() === 'processing') return 'Confirmando el resultado de tu pago…';
    return '';
  });

  readonly form = this.fb.nonNullable.group({
    destinationIdentifier: ['', [Validators.required, Validators.pattern(/\S/)]],
    amount: ['', [Validators.required, (c: AbstractControl) => this.validateAmount(c)]],
  });

  private request: PaymentRequest | null = null;
  /** Solo en memoria. Se reutiliza ante errores ambiguos; se renueva en cada intento nuevo. */
  private idempotencyKey: string | null = null;
  private pollSub?: Subscription;

  private get exponent(): number {
    return currencyExponent(this.context.currency);
  }

  ngOnInit(): void {
    this.form.controls.amount.updateValueAndValidity();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  // ---------- Validación visible ----------

  private validateAmount(c: AbstractControl): ValidationErrors | null {
    const v = String(c.value ?? '');
    if (!v.trim()) return null; // lo cubre "required"
    const minor = parseAmountToMinor(v, this.context ? this.exponent : 2);
    if (minor === null) return { amountFormat: true };
    if (minor <= 0) return { amountPositive: true };
    return null;
  }

  destinationError(): string | null {
    const c = this.form.controls.destinationIdentifier;
    if (!(c.touched && c.errors)) return null;
    if (c.errors['required'] || c.errors['pattern']) return 'Ingresa el destino del pago.';
    if (c.errors['server']) return 'Revisa este dato: no fue aceptado.';
    return 'Valor no válido.';
  }

  amountError(): string | null {
    const c = this.form.controls.amount;
    if (!(c.touched && c.errors)) return null;
    if (c.errors['required']) return 'Ingresa el monto.';
    if (c.errors['amountFormat'])
      return `Usa solo números con hasta ${this.exponent} decimales. Ejemplo: 12.50`;
    if (c.errors['amountPositive']) return 'El monto debe ser mayor que cero.';
    if (c.errors['server']) return 'El monto no está dentro de los límites permitidos para este pago.';
    return 'Valor no válido.';
  }

  formatAmount(minor: number | undefined, currency: string | undefined): string {
    return minor === undefined || !currency ? '' : formatMinor(minor, currency);
  }

  // ---------- Acciones ----------

  submitForm(): void {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focusFirstInvalid();
      return;
    }
    const minor = parseAmountToMinor(this.form.controls.amount.value, this.exponent);
    if (minor === null) return;

    this.request = {
      providerId: this.context.providerId,
      channel: this.context.channel,
      destination: {
        type: this.context.destinationType,
        identifier: this.form.controls.destinationIdentifier.value.trim(),
      },
      amountMinor: minor,
      currency: this.context.currency,
    };
    this.error.set(null);
    this.busy.set(true);
    this.api
      .preview(this.request)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (p) => {
          this.preview.set(p);
          this.step.set('confirm');
          this.focusHeading();
        },
        error: (e) => this.handleError(e, 'preview'),
      });
  }

  confirm(): void {
    if (this.busy() || !this.request) return;
    this.idempotencyKey ??= crypto.randomUUID();
    this.error.set(null);
    this.busy.set(true);
    this.api
      .create(this.request, this.idempotencyKey)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (p) => {
          this.payment.set(p);
          if (isTerminal(p.status)) {
            this.step.set('result');
            this.focusHeading();
          } else {
            this.startPolling(p.id);
          }
        },
        error: (e) => this.handleError(e, 'create'),
      });
  }

  editData(): void {
    if (this.busy()) return;
    this.idempotencyKey = null;
    this.error.set(null);
    this.step.set('form');
    this.focusHeading();
  }

  resumePolling(): void {
    const p = this.payment();
    if (!p) return;
    this.error.set(null);
    this.startPolling(p.id);
  }

  /** Nuevo intento tras FAILED/EXPIRED: clave nueva (nuevo Payment en el backend). */
  retry(): void {
    this.idempotencyKey = null;
    this.payment.set(null);
    this.error.set(null);
    this.step.set('confirm');
    this.focusHeading();
  }

  backToStart(): void {
    this.pollSub?.unsubscribe();
    this.idempotencyKey = null;
    this.request = null;
    this.preview.set(null);
    this.payment.set(null);
    this.error.set(null);
    this.form.reset();
    this.step.set('form');
    this.focusHeading();
  }

  // ---------- Internos ----------

  private startPolling(id: string): void {
    this.pollSub?.unsubscribe();
    this.step.set('processing');
    this.focusHeading();
    this.pollSub = this.api.poll(id).subscribe({
      next: (p) => this.payment.set(p),
      error: (e) => {
        this.handleError(e, 'poll');
        this.step.set('delayed');
      },
      complete: () => {
        if (isTerminal(this.payment()?.status)) {
          this.step.set('result');
        } else {
          this.step.set('delayed'); // tope de tiempo: resultado aún no disponible, no es un fallo
        }
        this.focusHeading();
      },
    });
  }

  private handleError(e: unknown, op: 'preview' | 'create' | 'poll'): void {
    const ue = toUserError(e, op);
    this.error.set(ue);
    if (op === 'create' && !ue.keepIdempotencyKey) {
      this.idempotencyKey = null;
    }
    if (op === 'preview') {
      this.applyServerFieldErrors(ue.fieldNames);
    }
    if (ue.sessionExpired) {
      this.sessionExpired.emit();
    }
    this.focusError();
  }

  private applyServerFieldErrors(names: string[]): void {
    for (const raw of names) {
      const n = raw.toLowerCase();
      const ctrl = n.includes('amount')
        ? this.form.controls.amount
        : n.includes('destination') || n.includes('identifier')
          ? this.form.controls.destinationIdentifier
          : null;
      if (ctrl) {
        ctrl.setErrors({ ...(ctrl.errors ?? {}), server: true });
        ctrl.markAsTouched();
      }
    }
  }

  private focusHeading(): void {
    setTimeout(() => this.heading?.nativeElement.focus());
  }

  private focusError(): void {
    setTimeout(() => this.errorBox?.nativeElement.focus());
  }

  private focusFirstInvalid(): void {
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('input.ng-invalid');
    el?.focus();
  }
}
```

```html
<!-- payment-flow.component.html -->
<section class="payment-flow" [attr.aria-busy]="busy() || step() === 'processing'">
  <p class="visually-hidden" role="status" aria-live="polite">{{ liveMessage() }}</p>

  @if (error(); as err) {
    <div #errorBox class="alert" role="alert" tabindex="-1">
      <strong>No se pudo completar la operación</strong>
      <p>{{ err.message }}</p>
    </div>
  }

  @switch (step()) {
    @case ('form') {
      <h2 #stepHeading tabindex="-1">Transferencia de bajo monto</h2>
      <form [formGroup]="form" (ngSubmit)="submitForm()" novalidate>
        <div class="field">
          <label for="destination-identifier">Destino del pago</label>
          <input
            id="destination-identifier"
            type="text"
            formControlName="destinationIdentifier"
            autocomplete="off"
            [attr.aria-invalid]="destinationError() ? 'true' : null"
            [attr.aria-describedby]="destinationError() ? 'destination-error' : null"
          />
          @if (destinationError(); as msg) {
            <p id="destination-error" class="field-error">{{ msg }}</p>
          }
        </div>

        <div class="field">
          <label for="amount">Monto ({{ context.currency }})</label>
          <input
            id="amount"
            type="text"
            inputmode="decimal"
            formControlName="amount"
            autocomplete="off"
            aria-describedby="amount-hint amount-error"
            [attr.aria-invalid]="amountError() ? 'true' : null"
          />
          <p id="amount-hint" class="hint">Usa punto o coma para los decimales. Ejemplo: 12.50</p>
          <p id="amount-error" class="field-error">{{ amountError() }}</p>
        </div>

        <button type="submit" [disabled]="busy()">
          {{ busy() ? 'Verificando…' : 'Continuar' }}
        </button>
      </form>
    }

    @case ('confirm') {
      @if (preview(); as p) {
        <h2 #stepHeading tabindex="-1">Confirma tu pago</h2>
        <dl class="summary">
          <dt>Destino</dt>
          <dd>{{ p.destination.displayName }}</dd>
          <dt>Identificador</dt>
          <dd>{{ p.destination.identifier }}</dd>
          <dt>Monto</dt>
          <dd>{{ formatAmount(p.amountMinor, p.currency) }}</dd>
        </dl>
        <div class="actions">
          <button type="button" (click)="confirm()" [disabled]="busy()">
            {{ busy() ? 'Enviando pago…' : 'Confirmar pago' }}
          </button>
          <button type="button" class="secondary" (click)="editData()" [disabled]="busy()">
            Editar datos
          </button>
        </div>
      }
    }

    @case ('processing') {
      <h2 #stepHeading tabindex="-1">Estamos confirmando tu pago</h2>
      <div class="spinner" aria-hidden="true"></div>
      <p>Esto puede tardar unos segundos. No cierres esta pantalla ni repitas el pago.</p>
    }

    @case ('delayed') {
      <h2 #stepHeading tabindex="-1">Aún no tenemos el resultado</h2>
      <p>Tu pago puede seguir en proceso. No lo repitas: consulta de nuevo o revisa tu historial.</p>
      <div class="actions">
        <button type="button" (click)="resumePolling()">Consultar de nuevo</button>
        <button type="button" class="secondary" (click)="viewHistory.emit()">Ver historial</button>
      </div>
    }

    @case ('result') {
      @if (payment(); as pay) {
        <h2 #stepHeading tabindex="-1">{{ resultLabel() }}</h2>
        <p>{{ resultMessage() }}</p>

        @if (terminalStatus() === 'SUCCEEDED') {
          <dl class="summary">
            <dt>Referencia de transacción</dt>
            <dd>{{ pay.transactionReference }}</dd>
            @if (preview(); as p) {
              <dt>Destino</dt>
              <dd>{{ p.destination.displayName }}</dd>
              <dt>Monto</dt>
              <dd>{{ formatAmount(p.amountMinor, p.currency) }}</dd>
            }
          </dl>
          <div class="actions">
            <button type="button" (click)="viewHistory.emit()">Ver historial</button>
            <button type="button" class="secondary" (click)="backToStart()">Hacer otro pago</button>
          </div>
        } @else {
          <div class="actions">
            <button type="button" (click)="retry()">Reintentar</button>
            <button type="button" class="secondary" (click)="backToStart()">Volver</button>
          </div>
        }
      }
    }
  }
</section>
```

```scss
// payment-flow.component.scss
.payment-flow {
  max-width: 32rem;
  margin: 0 auto;
  padding: 1rem;
  color: #1a1a1a;
}

h2:focus {
  outline: none;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.field {
  margin-bottom: 1rem;

  label {
    display: block;
    font-weight: 600;
    margin-bottom: 0.25rem;
  }

  input {
    width: 100%;
    padding: 0.5rem;
    font-size: 1rem;
    border: 2px solid #595959;
    border-radius: 4px;

    &[aria-invalid='true'] {
      border-color: #a30000;
    }
  }
}

.hint {
  color: #404040;
  font-size: 0.875rem;
  margin: 0.25rem 0 0;
}

.field-error {
  color: #a30000;
  font-size: 0.875rem;
  margin: 0.25rem 0 0;
}

.alert {
  border: 2px solid #a30000;
  background: #fff5f5;
  color: #5c0000;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
  border-radius: 4px;
}

.summary {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.25rem 1rem;

  dt {
    font-weight: 600;
  }

  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
}

.actions {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-top: 1rem;
}

button {
  padding: 0.6rem 1.2rem;
  font-size: 1rem;
  border-radius: 4px;
  border: 2px solid #0b3d91;
  background: #0b3d91;
  color: #fff;
  cursor: pointer;

  &.secondary {
    background: #fff;
    color: #0b3d91;
  }

  &:disabled {
    background: #595959;
    border-color: #595959;
    color: #fff;
    cursor: not-allowed;
  }
}

:is(button, input):focus-visible {
  outline: 3px solid #ffbf47;
  outline-offset: 2px;
}

.spinner {
  width: 2rem;
  height: 2rem;
  border: 4px solid #d0d0d0;
  border-top-color: #0b3d91;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 1rem 0;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}
```

```typescript
// payment-history.component.ts
import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { PaymentApiService } from './payment-api.service';
import { UserError, toUserError } from './payment-error.mapper';
import { formatMinor } from './money.util';
import { PaymentResponse, STATUS_LABELS, isTerminal } from './payment.models';

@Component({
  selector: 'app-payment-history',
  standalone: true,
  templateUrl: './payment-history.component.html',
})
export class PaymentHistoryComponent implements OnInit {
  private readonly api = inject(PaymentApiService);

  /** SUPUESTO PENDIENTE (S2): tamaño de página. */
  readonly pageSize = 10;

  readonly items = signal<PaymentResponse[]>([]);
  readonly page = signal(0);
  readonly hasNext = signal(false);
  readonly loading = signal(false);
  readonly error = signal<UserError | null>(null);

  @ViewChild('historyHeading') private heading?: ElementRef<HTMLElement>;

  ngOnInit(): void {
    this.load(0);
  }

  load(page: number): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.api.history(page, this.pageSize).subscribe({
      next: (r) => {
        this.items.set(r.items ?? []);
        this.page.set(r.page ?? page);
        const size = r.size ?? this.pageSize;
        this.hasNext.set(
          r.totalElements !== undefined
            ? (r.page + 1) * size < r.totalElements
            : (r.items?.length ?? 0) === size,
        );
        this.loading.set(false);
        setTimeout(() => this.heading?.nativeElement.focus());
      },
      error: (e) => {
        this.error.set(toUserError(e, 'history'));
        this.loading.set(false);
      },
    });
  }

  label(p: PaymentResponse): string {
    return isTerminal(p.status) ? STATUS_LABELS[p.status] : 'En proceso';
  }

  amount(p: PaymentResponse): string {
    return p.amountMinor !== undefined && p.currency ? formatMinor(p.amountMinor, p.currency) : '';
  }
}
```

```html
<!-- payment-history.component.html -->
<section [attr.aria-busy]="loading()">
  <h2 #historyHeading tabindex="-1">Historial de pagos</h2>

  <p role="status" aria-live="polite">{{ loading() ? 'Cargando historial…' : '' }}</p>

  @if (error(); as err) {
    <div role="alert">
      <p>{{ err.message }}</p>
      <button type="button" (click)="load(page())">Reintentar carga</button>
    </div>
  } @else if (!loading()) {
    @if (items().length === 0) {
      <p>Aún no tienes pagos en tu historial.</p>
    } @else {
      <table>
        <caption>Pagos realizados, del más reciente al más antiguo</caption>
        <thead>
          <tr>
            <th scope="col">Fecha</th>
            <th scope="col">Destino</th>
            <th scope="col">Monto</th>
            <th scope="col">Resultado</th>
            <th scope="col">Referencia</th>
          </tr>
        </thead>
        <tbody>
          @for (p of items(); track p.id) {
            <tr>
              <td>{{ p.completedAt ?? p.createdAt }}</td>
              <td>{{ p.destination?.displayName }}</td>
              <td>{{ amount(p) }}</td>
              <td>{{ label(p) }}</td>
              <td>{{ p.transactionReference ?? '—' }}</td>
            </tr>
          }
        </tbody>
      </table>

      <nav aria-label="Paginación del historial">
        <button type="button" (click)="load(page() - 1)" [disabled]="loading() || page() === 0">
          Anterior
        </button>
        <span>Página {{ page() + 1 }}</span>
        <button type="button" (click)="load(page() + 1)" [disabled]="loading() || !hasNext()">
          Siguiente
        </button>
      </nav>
    }
  }
</section>
```

```typescript
// payment-error.mapper.spec.ts
import { HttpErrorResponse } from '@angular/common/http';
import { toUserError } from './payment-error.mapper';

function http(status: number, error: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, error, url: '/payments' });
}

describe('toUserError', () => {
  it('401 marca sesión expirada', () => {
    const r = toUserError(http(401), 'create');
    expect(r.sessionExpired).toBeTrue();
  });

  it('409 en create conserva la Idempotency-Key', () => {
    expect(toUserError(http(409), 'create').keepIdempotencyKey).toBeTrue();
  });

  it('400 en preview no conserva la clave y extrae nombres de campo', () => {
    const r = toUserError(http(400, { fieldErrors: [{ field: 'amountMinor' }] }), 'preview');
    expect(r.keepIdempotencyKey).toBeFalse();
    expect(r.fieldNames).toEqual(['amountMinor']);
  });

  it('5xx en create conserva la clave y no expone texto del servidor', () => {
    const r = toUserError(http(500, { message: 'SQL error en tabla payments' }), 'create');
    expect(r.keepIdempotencyKey).toBeTrue();
    expect(r.message).not.toContain('SQL');
  });

  it('error de red (0) en create conserva la clave', () => {
    expect(toUserError(http(0), 'create').keepIdempotencyKey).toBeTrue();
  });

  it('cualquier error de poll pide consultar de nuevo, no repetir', () => {
    expect(toUserError(http(503), 'poll').message).toContain('no lo repitas');
  });
});
```

```typescript
// payment-api.service.spec.ts
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { POLL_INTERVAL_MS, PaymentApiService } from './payment-api.service';
import { PaymentRequest } from './payment.models';

describe('PaymentApiService', () => {
  let svc: PaymentApiService;
  let http: HttpTestingController;

  const req: PaymentRequest = {
    providerId: 'p1',
    channel: 'c1',
    destination: { type: 't', identifier: 'x' },
    amountMinor: 1250,
    currency: 'PEN',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(PaymentApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('create envía el header Idempotency-Key', () => {
    svc.create(req, 'key-1').subscribe();
    const r = http.expectOne('/payments');
    expect(r.request.method).toBe('POST');
    expect(r.request.headers.get('Idempotency-Key')).toBe('key-1');
    r.flush({ id: 'a', status: 'PENDING' });
  });

  it('poll se detiene en el primer estado terminal', fakeAsync(() => {
    const seen: string[] = [];
    let done = false;
    svc.poll('a').subscribe({ next: (p) => seen.push(p.status), complete: () => (done = true) });

    tick(0);
    http.expectOne('/payments/a').flush({ id: 'a', status: 'PENDING' });
    tick(POLL_INTERVAL_MS);
    http.expectOne('/payments/a').flush({ id: 'a', status: 'SUCCEEDED', transactionReference: 'R1' });

    expect(seen).toEqual(['PENDING', 'SUCCEEDED']);
    expect(done).toBeTrue();
    tick(POLL_INTERVAL_MS * 3); // no debe haber más peticiones (http.verify lo comprueba)
  }));
});
```

## Resumen de evidencia y estado

- **NO EJECUTADO:** `ng build`, `ng test --watch=false` y todos los pasos manuales de la sección 9 del `frontend_spec.md`. Las specs pueden requerir ajustes al correrlas.
- **Sin spec de componente:** `PaymentFlowComponent` y `PaymentHistoryComponent` quedan verificados solo con los pasos manuales de la sección 9.
- **CA5 y CA6** no son verificables ni implementables desde el frontend (métrica de negocio y evento emitido por el backend).
- **Pendientes bloqueantes para integrar:** S1, S2, S3 y S6 (rutas, nombres de campos, contexto del pago y códigos del 409), según el `frontend_spec.md`.