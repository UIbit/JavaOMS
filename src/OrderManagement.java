import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * My Order Management System
 * --------------------------
 * This system handles order processing with:
 * - Configurable trading hours (sends logon/logout messages)
 * - Rate limiting (orders per second)
 * - Order queue with modify/cancel support
 * - Response tracking with latency measurement
 *
 * Design Notes:
 * I used ScheduledExecutorService for periodic tasks because it's efficient
 * for timer-based operations. The lock ensures thread safety for shared resources.
 * The queue uses both LinkedList (FIFO) and HashMap (for quick lookups) - this
 * combo gives good performance for our needs.
 *
 * Important: This is production-grade code but simplified for the assignment scope.
 */

public class OrderManagement {
    // Config settings
    private final LocalTime tradingStart;
    private final LocalTime tradingEnd;
    private final int maxOrdersPerSecond;

    // Runtime state
    private volatile boolean tradingActive = false;
    private final AtomicInteger ordersSentThisSecond = new AtomicInteger(0);
    private final Queue<OrderRequest> pendingOrders = new LinkedList<>();
    private final Map<Long, OrderRequest> queuedOrderLookup = new HashMap<>();
    private final Map<Long, Long> sentOrderTimestamps = new ConcurrentHashMap<>();
    private final Lock stateLock = new ReentrantLock();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);

    public OrderManagement(LocalTime start, LocalTime end, int maxPerSecond) {
        this.tradingStart = start;
        this.tradingEnd = end;
        this.maxOrdersPerSecond = maxPerSecond;

        // Initial check for trading window
        verifyTradingWindow();

        // Setup periodic tasks
        timer.scheduleAtFixedRate(this::processQueuedOrders, 0, 1, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::verifyTradingWindow, 0, 1, TimeUnit.MINUTES);
    }

    // Handle incoming order requests
    public void onData(OrderRequest order) {
        if (!tradingActive) {
            System.out.println("[Rejected] Order outside trading hours");
            return;
        }

        stateLock.lock();
        try {
            switch (order.m_requestType) {
                case New:
                    addNewOrder(order);
                    break;
                case Modify:
                    updateExistingOrder(order);
                    break;
                case Cancel:
                    removeOrder(order);
                    break;
                default:
                    System.out.println("Unsupported request type");
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void addNewOrder(OrderRequest order) {
        if (ordersSentThisSecond.get() < maxOrdersPerSecond) {
            ordersSentThisSecond.incrementAndGet();
            transmitOrder(order);
            sentOrderTimestamps.put(order.m_orderId, System.currentTimeMillis());
        } else {
            pendingOrders.add(order);
            queuedOrderLookup.put(order.m_orderId, order);
        }
    }

    private void updateExistingOrder(OrderRequest order) {
        OrderRequest queued = queuedOrderLookup.get(order.m_orderId);
        if (queued != null) {
            queued.m_price = order.m_price;
            queued.m_qty = order.m_qty;
        }
    }

    private void removeOrder(OrderRequest order) {
        OrderRequest queued = queuedOrderLookup.remove(order.m_orderId);
        if (queued != null) {
            pendingOrders.remove(queued);
        }
    }

    // Handle exchange responses
    public void onData(OrderResponse response) {
        Long sentTime = sentOrderTimestamps.remove(response.m_orderId);
        if (sentTime != null) {
            long timeTaken = System.currentTimeMillis() - sentTime;
            recordResponse(response, timeTaken);
        }
    }

    private void recordResponse(OrderResponse response, long latency) {
        // In real system, this would write to database/file
        System.out.printf("Response: ID=%d, Status=%s, Latency=%dms%n",
                response.m_orderId, response.m_responseType, latency);
    }

    // Exchange communication
    public void transmitOrder(OrderRequest request) {
        System.out.println("Sending order: " + request.m_orderId);
    }

    public void sendLogon() {
        System.out.println(">> Logon message sent");
    }

    public void sendLogout() {
        System.out.println(">> Logout message sent");
    }

    private void processQueuedOrders() {
        stateLock.lock();
        try {
            ordersSentThisSecond.set(0);
            int availableSlots = maxOrdersPerSecond;

            while (availableSlots > 0 && !pendingOrders.isEmpty()) {
                OrderRequest nextOrder = pendingOrders.poll();
                if (queuedOrderLookup.remove(nextOrder.m_orderId) != null) {
                    transmitOrder(nextOrder);
                    sentOrderTimestamps.put(nextOrder.m_orderId, System.currentTimeMillis());
                    ordersSentThisSecond.incrementAndGet();
                    availableSlots--;
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void verifyTradingWindow() {
        LocalTime currentTime = LocalTime.now();
        boolean shouldBeActive = !currentTime.isBefore(tradingStart)
                && !currentTime.isAfter(tradingEnd);

        if (shouldBeActive && !tradingActive) {
            tradingActive = true;
            sendLogon();
        } else if (!shouldBeActive && tradingActive) {
            tradingActive = false;
            sendLogout();
        }
    }

    // Cleanup resources
    public void stop() {
        timer.shutdown();
        try {
            if (!timer.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                timer.shutdownNow();
            }
        } catch (InterruptedException e) {
            timer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Test harness
    public static void main(String[] args) throws Exception {
        System.out.println("=== Starting OMS Test ===");

        // Setup trading window (current time ±1 hour)
        OrderManagement oms = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                100
        );

        // Brief pause for initialization
        Thread.sleep(50);

        // Create test order
        OrderRequest testOrder = new OrderRequest();
        testOrder.m_orderId = 1001;
        testOrder.m_requestType = RequestType.New;
        testOrder.m_price = 155.75;
        testOrder.m_qty = 50;
        testOrder.m_side = 'S';
        oms.onData(testOrder);

        // Simulate exchange response
        OrderResponse testResponse = new OrderResponse();
        testResponse.m_orderId = 1001;
        testResponse.m_responseType = ResponseType.Accept;
        oms.onData(testResponse);

        // Clean shutdown
        Thread.sleep(100);
        oms.stop();
        System.out.println("=== Test Completed ===");
    }
}
