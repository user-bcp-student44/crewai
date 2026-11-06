// PaymentMapper.java
package pe.bcp.login.resource;

import java.util.List;
import pe.bcp.login.domain.Destination;
import pe.bcp.login.domain.HistoryPage;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentPreview;
import pe.bcp.login.dto.DestinationDto;
import pe.bcp.login.dto.HistoryPageResponse;
import pe.bcp.login.dto.PaymentResponse;
import pe.bcp.login.dto.PreviewResponse;
import pe.bcp.login.dto.ReceiptResponse;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    static DestinationDto toDestinationDto(Destination d) {
        return new DestinationDto(d.type(), d.identifier(), d.displayName());
    }

    public static PreviewResponse toPreviewResponse(PaymentPreview p) {
        return new PreviewResponse(p.providerId(), p.channel(), toDestinationDto(p.destination()),
                p.amountMinor(), p.currency());
    }

    public static PaymentResponse toPaymentResponse(Payment p) {
        return new PaymentResponse(
                p.id().toString(),
                p.status().name(),
                p.status().isTerminal(),
                p.providerId(),
                p.channel(),
                toDestinationDto(p.destination()),
                p.amountMinor(),
                p.currency(),
                p.transactionReference(),
                p.failureReason(),
                p.createdAt(),
                p.expiresAt(),
                p.completedAt());
    }

    public static ReceiptResponse toReceiptResponse(Payment p) {
        return new ReceiptResponse(
                p.id().toString(),
                p.transactionReference(),
                p.providerId(),
                p.channel(),
                toDestinationDto(p.destination()),
                p.amountMinor(),
                p.currency(),
                p.completedAt());
    }

    public static HistoryPageResponse toHistoryResponse(HistoryPage page) {
        List<PaymentResponse> items = page.items().stream().map(PaymentMapper::toPaymentResponse).toList();
        return new HistoryPageResponse(items, page.page(), page.size(), page.totalElements());
    }
}