// HistoryPage.java
package pe.bcp.login.domain;

import java.util.List;

public record HistoryPage(List<Payment> items, int page, int size, long totalElements) {
}