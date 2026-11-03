package in.nagarsetu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class NagarsetuApplication {

	public static void main(String[] args) {
		// Render (and Heroku-style hosts) expose Postgres as postgresql://user:pass@host:port/db; Spring wants JDBC.
		String databaseUrl = System.getenv("DATABASE_URL");
		if (databaseUrl != null && !databaseUrl.isBlank()) {
			URI u = URI.create(databaseUrl.trim());
			String[] creds = u.getRawUserInfo().split(":", 2);
			System.setProperty("spring.datasource.url",
					"jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath());
			System.setProperty("spring.datasource.username", URLDecoder.decode(creds[0], StandardCharsets.UTF_8));
			System.setProperty("spring.datasource.password",
					creds.length > 1 ? URLDecoder.decode(creds[1], StandardCharsets.UTF_8) : "");
		}
		SpringApplication.run(NagarsetuApplication.class, args);
	}

}
