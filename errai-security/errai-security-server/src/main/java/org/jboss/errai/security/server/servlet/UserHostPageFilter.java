package org.jboss.errai.security.server.servlet;

import static org.jboss.errai.security.Properties.USER_ON_HOSTPAGE_ENABLED;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.jboss.errai.marshalling.server.MappingContextSingleton;
import org.jboss.errai.marshalling.server.ServerMarshalling;
import org.jboss.errai.security.server.properties.ErraiAppProperties;
import org.jboss.errai.security.shared.api.SecurityConstants;
import org.jboss.errai.security.shared.api.identity.User;
import org.jboss.errai.security.shared.service.AuthenticationService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Serializes and adds the authenticated user to a JavaScript variable in the
 * application's host page. This is useful in case the login page lives outside
 * the GWT application as it makes instances of {@link User} immediately
 * injectable (without requiring a server round-trip.)
 * 
 * @author Christian Sadilek <csadilek@redhat.com>
 */
@WebFilter(filterName = "ErraiUserHostPageFilter", urlPatterns = { "/index.jsp", "/index.html" })
public class UserHostPageFilter implements Filter {

  @Inject
  private AuthenticationService authenticationService;

  @Inject
  @ErraiAppProperties
  private Properties properties;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    // Initialize server side marshaller
    MappingContextSingleton.get();
  }

  @Override
  public void destroy() {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
          ServletException {

    if (!isUserOnHostPageEnabled()) {
      chain.doFilter(request, response);
    }
    else {
      final CharResponseWrapper wrappedResponse = new CharResponseWrapper((HttpServletResponse) response);
      chain.doFilter(request, wrappedResponse);

      final User user = authenticationService.getUser();
      final String output;
      
      if (user != null) {
        final String injectedScript = "<script>var " + 
                SecurityConstants.ERRAI_SECURITY_CONTEXT_DICTIONARY + "  = {\"" +
                SecurityConstants.DICTIONARY_USER + "\": '" + 
                ServerMarshalling.toJSON(user) + "'};" + "</script>";

        final Document document = Jsoup.parse(wrappedResponse.toString());
        document.head().append(injectedScript);
        output = document.html();
      }
      else {
        output = wrappedResponse.toString();
      }
      
      byte[] outputBytes = output.getBytes("UTF-8");
      response.setContentLength(outputBytes.length);
      response.getOutputStream().write(outputBytes);
    }
  }

  private boolean isUserOnHostPageEnabled() {
    if (properties.containsKey(USER_ON_HOSTPAGE_ENABLED)) {
      return Boolean.parseBoolean(properties.getProperty(USER_ON_HOSTPAGE_ENABLED));
    }
    return false;
  }

  class CharResponseWrapper extends HttpServletResponseWrapper {

    protected CharArrayWriter charWriter = new CharArrayWriter();

    protected ServletOutputStream outputStream = new ServletOutputStream() {
      @Override
      public void write(int b) throws IOException {
        charWriter.write(b);
      }
    };

    protected PrintWriter writer = new PrintWriter(charWriter);

    public CharResponseWrapper(final HttpServletResponse response) {
      super(response);
    }

    public ServletOutputStream getOutputStream() throws IOException {
      return outputStream;
    }

    public PrintWriter getWriter() throws IOException {
      return writer;
    }

    public String toString() {
      return charWriter.toString();
    }
  }
}