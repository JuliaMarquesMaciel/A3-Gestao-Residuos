import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConexaoMySQL {
    public static void main(String[] args) {
        // Endereço da base de dados MySQL Workbench
        String url = "jdbc:mysql://localhost:3306/db_residuos_hospitalares";
        String usuario = "root"; // Teu utilizador do MySQL
        String senha = "sua_senha"; // Tua palavra-passe do MySQL

        try {
            Connection conexao = DriverManager.getConnection(url, usuario, senha);
            System.out.println("Conexão com o MySQL realizada com sucesso!");
        } catch (SQLException e) {
            System.err.println("Erro ao conectar ao MySQL: " + e.getMessage());
        }
    }
}
