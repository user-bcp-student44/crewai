npm i -D @playwright/test
npx playwright install chromium
# En otra terminal: servir la app Angular (comando del proyecto, p. ej. npx ng serve)
export ONBOARDING_URL="http://localhost:4200/<ruta-onboarding>"
npx playwright test e2e/onboarding.spec.ts
# Solo si la app NO define campos de paso (token ONBOARDING_STEP_FIELDS vacío):
STEP_FIELDS_EMPTY=true npx playwright test e2e/onboarding.spec.ts -g "UI-03"