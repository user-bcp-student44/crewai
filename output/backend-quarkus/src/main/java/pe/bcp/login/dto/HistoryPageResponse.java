// HistoryPageResponse.java
package pe.bcp.login.dto;

import java.util.List;

public record HistoryPageResponse(List<PaymentResponse> items, int page, int size, long totalElements) {
}