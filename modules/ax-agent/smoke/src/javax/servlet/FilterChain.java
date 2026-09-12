package javax.servlet;

public interface FilterChain {
    void doFilter(ServletRequest request, ServletResponse response) throws Exception;
}
