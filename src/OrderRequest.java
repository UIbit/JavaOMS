public class OrderRequest {
    public int m_symbolId;
    public double m_price;
    public long m_qty;
    public char m_side; // 'B' or 'S'
    public long m_orderId;
    public RequestType m_requestType;
}
