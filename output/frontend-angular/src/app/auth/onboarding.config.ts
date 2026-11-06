// onboarding.config.ts
import { InjectionToken } from '@angular/core';
import { StepFieldDefinition } from './onboarding.models';

export interface OnboardingConfig {
  /** URL base del BFF. Vacío = mismo origen (SP-2). */
  apiBaseUrl: string;
  routes: {
    /** Ruta de login de la app (SP-2). Si no existe, no se muestra el botón. */
    login?: string;
    /** Mapa clave lógica de `nextAction.target` -> ruta de la app (SP-7). */
    targets: Record<string, string>;
  };
}

export const ONBOARDING_CONFIG = new InjectionToken<OnboardingConfig>('ONBOARDING_CONFIG', {
  providedIn: 'root',
  factory: () => ({ apiBaseUrl: '', routes: { targets: {} } }),
});

/** stepId -> campos del paso. Vacío por defecto: pendiente de Cumplimiento y Producto (SP-4). */
export const ONBOARDING_STEP_FIELDS = new InjectionToken<Record<string, StepFieldDefinition[]>>(
  'ONBOARDING_STEP_FIELDS',
  { providedIn: 'root', factory: () => ({}) },
);