import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API HTTP mínima (sem frameworks) que liga o front-end ao MySQL.
 * Usa só o JDK (com.sun.net.httpserver) e JDBC.
 *
 * Variáveis de ambiente (opcionais):
 *   DB_URL, DB_USER, DB_PASSWORD, PORTA
 *
 * Rotas:
 *   GET  /api/setores  /api/classes  /api/funcionarios  /api/residuos
 *        /api/coletas  /api/prazos   /api/relatorio
 *   POST /api/residuos   POST /api/coletas
 */
public class ServidorAPI {

    static final String URL = env("DB_URL",
            "jdbc:mysql://localhost:3306/gestao_residuos_hospitalares?useUnicode=true&characterEncoding=UTF-8");
    static final String USUARIO = env("DB_USER", "usr_operador");
    static final String SENHA = env("DB_PASSWORD", "");

    static String env(String nome, String padrao) {
        String v = System.getenv(nome);
        return (v == null || v.isEmpty()) ? padrao : v;
    }

    public static void main(String[] args) throws IOException {
        int porta = Integer.parseInt(env("PORTA", "8080"));
        HttpServer servidor = HttpServer.create(new InetSocketAddress(porta), 0);
        servidor.createContext("/api", ServidorAPI::tratar);
        servidor.start();
        System.out.println("API no ar em http://localhost:" + porta + "/api");
    }

    // ------------------------------------------------------------------
    // Roteamento
    // ------------------------------------------------------------------
    static void tratar(HttpExchange ex) throws IOException {
        // CORS: permite o front-end (aberto de outro endereço) chamar a API
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        String metodo = ex.getRequestMethod();
        String rota = ex.getRequestURI().getPath();
        try {
            if (metodo.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (metodo.equals("GET")) {
                switch (rota) {
                    case "/api/setores":      responder(ex, 200, json(consultar(SQL_SETORES))); return;
                    case "/api/classes":      responder(ex, 200, json(consultar(SQL_CLASSES))); return;
                    case "/api/funcionarios": responder(ex, 200, json(consultar(SQL_FUNCIONARIOS))); return;
                    case "/api/residuos":     responder(ex, 200, json(consultar(SQL_RESIDUOS))); return;
                    case "/api/coletas":      responder(ex, 200, json(consultar(SQL_COLETAS))); return;
                    case "/api/prazos":       responder(ex, 200, json(consultar(SQL_PRAZOS))); return;
                    case "/api/relatorio":    responder(ex, 200, json(consultar(SQL_RELATORIO))); return;
                    default: break;
                }
            } else if (metodo.equals("POST")) {
                Map<String, String> corpo = lerJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (rota.equals("/api/residuos")) { criarResiduo(corpo); responder(ex, 201, "{\"ok\":true}"); return; }
                if (rota.equals("/api/coletas"))  { criarColeta(corpo);  responder(ex, 201, "{\"ok\":true}"); return; }
            }
            responder(ex, 404, erro("Rota não encontrada."));
        } catch (IllegalArgumentException e) {
            responder(ex, 422, erro(e.getMessage()));
        } catch (SQLException e) {
            responderErroSql(ex, e);
        } catch (Exception e) {
            e.printStackTrace();
            responder(ex, 500, erro("Erro interno do servidor."));
        }
    }

    static void responderErroSql(HttpExchange ex, SQLException e) throws IOException {
        int codigo = e.getErrorCode();
        if (codigo == 3819) responder(ex, 422, erro("Valor inválido: viola uma regra do banco (quantidade maior que zero, validade não anterior à geração)."));
        else if (codigo == 1452) responder(ex, 422, erro("Um dos itens informados não existe."));
        else if (codigo == 1062) responder(ex, 409, erro("Registro duplicado (ex.: este resíduo já foi coletado)."));
        else if (codigo == 1142 || codigo == 1143 || codigo == 1044) responder(ex, 403, erro("Este usuário do banco não tem permissão para esta operação."));
        else {
            e.printStackTrace();                       // detalhes só no console do servidor
            responder(ex, 500, erro("Erro ao acessar o banco de dados."));
        }
    }

    // ------------------------------------------------------------------
    // Consultas (SELECT)
    // ------------------------------------------------------------------
    static final String SQL_SETORES =
        "SELECT s.id_setor, s.nome_setor, h.nome AS nome_hospital "
      + "FROM setor_gerador s JOIN hospital h ON h.id_hospital = s.id_hospital ORDER BY h.nome, s.nome_setor";
    static final String SQL_CLASSES =
        "SELECT id_classe, nome_classe, periculosidade FROM classe_residuo ORDER BY nome_classe";
    static final String SQL_FUNCIONARIOS =
        "SELECT id_funcionario, nome, funcao FROM funcionario ORDER BY nome";
    static final String SQL_RESIDUOS =
        "SELECT r.id_residuo, s.nome_setor, c.nome_classe, r.quantidade, r.unidade_medida, "
      + "r.data_geracao, r.validade_armazenamento, r.status_atual "
      + "FROM residuo r JOIN setor_gerador s ON s.id_setor = r.id_setor "
      + "JOIN classe_residuo c ON c.id_classe = r.id_classe ORDER BY r.id_residuo DESC";
    static final String SQL_COLETAS =
        "SELECT ci.id_coleta, ci.id_residuo, c.nome_classe, f.nome AS nome_funcionario, ci.data_hora "
      + "FROM coleta_interna ci JOIN residuo r ON r.id_residuo = ci.id_residuo "
      + "JOIN classe_residuo c ON c.id_classe = r.id_classe "
      + "JOIN funcionario f ON f.id_funcionario = ci.id_funcionario ORDER BY ci.data_hora DESC";
    static final String SQL_PRAZOS =
        "SELECT r.id_residuo, a.`local`, a.prazo_limite "
      + "FROM armazenamento a JOIN coleta_interna ci ON ci.id_coleta = a.id_coleta "
      + "JOIN residuo r ON r.id_residuo = ci.id_residuo WHERE a.data_saida IS NULL ORDER BY a.prazo_limite";
    static final String SQL_RELATORIO =
        "SELECT hospital, nome_classe, tipo_tratamento, qtd_residuos, total_kg FROM vw_relatorio_fiscalizacao";

    static List<Map<String, Object>> consultar(String sql) throws SQLException {
        List<Map<String, Object>> linhas = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(URL, USUARIO, SENHA);
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> linha = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String nome = md.getColumnLabel(i);
                    int tipo = md.getColumnType(i);
                    if (rs.getObject(i) == null) linha.put(nome, null);
                    else if (tipo == Types.TIMESTAMP) linha.put(nome, rs.getTimestamp(i).toLocalDateTime().toString());
                    else if (tipo == Types.DATE) linha.put(nome, rs.getDate(i).toString());
                    else if (tipo == Types.DECIMAL || tipo == Types.NUMERIC || tipo == Types.INTEGER
                          || tipo == Types.BIGINT || tipo == Types.SMALLINT || tipo == Types.TINYINT) linha.put(nome, rs.getBigDecimal(i));
                    else linha.put(nome, rs.getString(i));
                }
                linhas.add(linha);
            }
        }
        return linhas;
    }

    // ------------------------------------------------------------------
    // Gravações (INSERT) - sempre com PreparedStatement
    // ------------------------------------------------------------------
    static void criarResiduo(Map<String, String> d) throws SQLException {
        int idSetor = inteiro(d, "id_setor");
        int idClasse = inteiro(d, "id_classe");
        BigDecimal quantidade = decimal(d, "quantidade");
        String unidade = texto(d, "unidade_medida", 10);
        Timestamp geracao = dataHora(d, "data_geracao");
        java.sql.Date validade = data(d, "validade_armazenamento");
        try (Connection con = DriverManager.getConnection(URL, USUARIO, SENHA);
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO residuo (id_setor, id_classe, quantidade, unidade_medida, data_geracao, validade_armazenamento) "
               + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, idSetor);
            ps.setInt(2, idClasse);
            ps.setBigDecimal(3, quantidade);
            ps.setString(4, unidade);
            ps.setTimestamp(5, geracao);
            ps.setDate(6, validade);
            ps.executeUpdate();
        }
    }

    static void criarColeta(Map<String, String> d) throws SQLException {
        int idResiduo = inteiro(d, "id_residuo");
        int idFuncionario = inteiro(d, "id_funcionario");
        Timestamp quando = dataHora(d, "data_hora");
        try (Connection con = DriverManager.getConnection(URL, USUARIO, SENHA);
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO coleta_interna (id_residuo, id_funcionario, data_hora) VALUES (?, ?, ?)")) {
            ps.setInt(1, idResiduo);
            ps.setInt(2, idFuncionario);
            ps.setTimestamp(3, quando);
            ps.executeUpdate();     // o trigger trg_coleta_status muda o status do resíduo para COLETADO
        }
    }

    // ------------------------------------------------------------------
    // Validação dos campos recebidos
    // ------------------------------------------------------------------
    static String obrigatorio(Map<String, String> d, String campo) {
        String v = d.get(campo);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Campo obrigatório sem valor: " + campo);
        return v.trim();
    }

    static int inteiro(Map<String, String> d, String campo) {
        try {
            int v = Integer.parseInt(obrigatorio(d, campo));
            if (v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Campo inválido: " + campo);
        }
    }

    static BigDecimal decimal(Map<String, String> d, String campo) {
        try {
            BigDecimal v = new BigDecimal(obrigatorio(d, campo).replace(',', '.'));
            if (v.signum() <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A quantidade deve ser um número maior que zero.");
        }
    }

    static String texto(Map<String, String> d, String campo, int max) {
        String v = obrigatorio(d, campo);
        if (v.length() > max) throw new IllegalArgumentException("Campo muito longo: " + campo);
        return v;
    }

    static Timestamp dataHora(Map<String, String> d, String campo) {
        String v = obrigatorio(d, campo).replace('T', ' ');
        if (v.length() == 16) v += ":00";
        try {
            return Timestamp.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Data/hora inválida: " + campo);
        }
    }

    static java.sql.Date data(Map<String, String> d, String campo) {
        try {
            return java.sql.Date.valueOf(obrigatorio(d, campo));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Data inválida: " + campo);
        }
    }

    // ------------------------------------------------------------------
    // JSON simples (sem bibliotecas)
    // ------------------------------------------------------------------
    // Lê um objeto JSON "plano": {"campo": "texto" ou número, ...}
    static Map<String, String> lerJson(String texto) {
        Map<String, String> mapa = new HashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|null)").matcher(texto);
        while (m.find()) {
            String valor = m.group(2);
            if (valor.startsWith("\"")) valor = valor.substring(1, valor.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            mapa.put(m.group(1), valor.equals("null") ? null : valor);
        }
        return mapa;
    }

    static String json(List<Map<String, Object>> linhas) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < linhas.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            int c = 0;
            for (Map.Entry<String, Object> e : linhas.get(i).entrySet()) {
                if (c++ > 0) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(valorJson(e.getValue()));
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    static String valorJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        return "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    static String erro(String mensagem) {
        return "{\"erro\":" + valorJson(mensagem) + "}";
    }

    static void responder(HttpExchange ex, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
