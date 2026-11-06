// api-config.ts
import { InjectionToken } from '@angular/core';

/**
 * URL base del BFF. SUPUESTO PENDIENTE (S5): se asume ruta relativa con proxy
 * de desarrollo. Sobrescribir en app.config.ts según el entorno.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '/api/v1',
});