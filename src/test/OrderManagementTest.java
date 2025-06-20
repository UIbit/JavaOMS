import java.time.LocalTime;

/**
 * Tests for the OrderManagement system.
 * This uses plain Java and does not require JUnit.
 */
public class OrderManagementTest {

    public static void main(String[] args) {
        System.out.println("=== Starting OrderManagement Tests ===");

        // Test 1: Order rejection outside trading window
        testOrderRejectionOutsideWindow();

        // Test 2: Order acceptance inside trading window
        testOrderAcceptanceInsideWindow();

        // Test 3: Order throttling (only 1 order per second)
        testOrderThrottling();

        // Test 4: Order modification in queue
        testOrderModify();

        // Test 5: Order cancellation in queue
        testOrderCancel();

        System.out.println("=== All Tests Completed ===");
    }

    private static void testOrderRejectionOutsideWindow() {
        System.out.println("\n--- Test: Order Rejection Outside Trading Window ---");
        // Set window to 1 hour ago to 30 minutes ago (current time is outside)
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(2),
                LocalTime.now().minusHours(1),
                100
        );
        OrderRequest req = new OrderRequest();
        req.m_orderId = 101;
        req.m_requestType = RequestType.New;
        req.m_price = 100.0;
        req.m_qty = 10;
        req.m_side = 'B';
        om.onData(req);
        // Expected: "Order rejected: outside trading window."
    }

    private static void testOrderAcceptanceInsideWindow() {
        System.out.println("\n--- Test: Order Acceptance Inside Trading Window ---");
        // Set window to now minus 1 hour to now plus 1 hour (inside window)
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                100
        );
        OrderRequest req = new OrderRequest();
        req.m_orderId = 102;
        req.m_requestType = RequestType.New;
        req.m_price = 100.0;
        req.m_qty = 10;
        req.m_side = 'B';
        om.onData(req);
        // Expected: "Sending order: 102"
    }

    private static void testOrderThrottling() {
        System.out.println("\n--- Test: Order Throttling (1 order/sec) ---");
        // Only 1 order per second
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                1
        );
        OrderRequest req1 = new OrderRequest();
        req1.m_orderId = 103;
        req1.m_requestType = RequestType.New;
        req1.m_price = 100.0;
        req1.m_qty = 10;
        req1.m_side = 'B';
        om.onData(req1); // Should be sent
        OrderRequest req2 = new OrderRequest();
        req2.m_orderId = 104;
        req2.m_requestType = RequestType.New;
        req2.m_price = 100.0;
        req2.m_qty = 10;
        req2.m_side = 'B';
        om.onData(req2); // Should be queued
        // Note: In a real test, you might wait a second to see if the queued order is sent
    }

    private static void testOrderModify() {
        System.out.println("\n--- Test: Order Modification in Queue ---");
        // Only 1 order per second
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                1
        );
        OrderRequest req1 = new OrderRequest();
        req1.m_orderId = 105;
        req1.m_requestType = RequestType.New;
        req1.m_price = 100.0;
        req1.m_qty = 10;
        req1.m_side = 'B';
        om.onData(req1); // Should be sent
        OrderRequest req2 = new OrderRequest();
        req2.m_orderId = 105; // Same ID
        req2.m_requestType = RequestType.Modify;
        req2.m_price = 105.0;
        req2.m_qty = 20;
        req2.m_side = 'B';
        om.onData(req2); // Should modify queued order (if queued)
    }

    private static void testOrderCancel() {
        System.out.println("\n--- Test: Order Cancellation in Queue ---");
        // Only 1 order per second
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                1
        );
        OrderRequest req1 = new OrderRequest();
        req1.m_orderId = 106;
        req1.m_requestType = RequestType.New;
        req1.m_price = 100.0;
        req1.m_qty = 10;
        req1.m_side = 'B';
        om.onData(req1); // Should be sent
        OrderRequest req2 = new OrderRequest();
        req2.m_orderId = 106; // Same ID
        req2.m_requestType = RequestType.Cancel;
        req2.m_price = 100.0;
        req2.m_qty = 10;
        req2.m_side = 'B';
        om.onData(req2); // Should cancel queued order (if queued)
    }
}
