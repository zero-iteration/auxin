package traceapp;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * The activation point. Its descriptor is
 * {@code doFilter(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;Ljavax/servlet/FilterChain;)V},
 * which is what {@code EntrySignatures} matches on — byte-for-byte what a real Tomcat filter has.
 */
public final class SearchFilter implements Filter {

    private final FilterChain chain;

    public SearchFilter(FilterChain chain) { this.chain = chain; }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain unused) {
        try {
            chain.doFilter(request, response);
            response.setStatus(200);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }
}
