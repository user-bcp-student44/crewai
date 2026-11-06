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