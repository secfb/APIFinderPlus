package database;

import com.alibaba.fastjson2.JSONObject;
import model.PathTreeModel;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static utils.CastUtils.isNotEmptyStr;
import static utils.PathTreeUtils.deepMergeJsonTree;
import static utils.BurpPrintUtils.*;

public class PathTreeTable {
    //数据表名称
    public static String tableName = "PATH_TREE";

    //创建 基于 record_urls 生成的每个域名的 路径结构 树
    static String creatTableSQL = "CREATE TABLE IF NOT EXISTS "+ tableName +" (\n"
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,\n"  //自增的id
            + " req_proto TEXT NOT NULL,\n"
            + " req_host_port TEXT NOT NULL,\n"    //请求域名:端口
            + " path_tree TEXT NOT NULL,\n"   //根树的序列化Json数据
            + " basic_path_num INTEGER NOT NULL DEFAULT 0\n"  //基于多少个路径计算出来的根树,最好使用根树的稳定 hash
            + ");";

    //插入数据库
    public static synchronized int insertOrUpdatePathTree(PathTreeModel pathTreeModel) {
        String reqProto = pathTreeModel.getReqProto();
        String reqHostPort = pathTreeModel.getReqHostPort();
        Integer newBasicPathNum = pathTreeModel.getBasicPathNum();
        JSONObject newPathTree = pathTreeModel.getPathTree();

        int generatedId = -1; // 默认ID值，如果没有生成ID，则保持此值

        //查询 是否已存在记录
        String checkSql = "SELECT id,path_tree,basic_path_num FROM "+ tableName +" WHERE req_host_port = ? and req_proto = ?;";
        try (Connection conn = DBService.getInstance().getNewConn(); PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, reqHostPort);
            checkStmt.setString(2, reqProto);

            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {

                int selectedId = rs.getInt("id");
                String oldPathTree = rs.getString("path_tree");
                int oldBasicPathNum = rs.getInt("basic_path_num");

                //合并新旧pathNum 输入的PATH TREE 是基于新找到的PATH 因此是增量的
                newBasicPathNum = Math.max(0, oldBasicPathNum) + Math.max(0, newBasicPathNum);

                //合并新旧Json树
                if (isNotEmptyStr(oldPathTree)){
                    JSONObject oldTree = JSONObject.parse(oldPathTree);
                    newPathTree = deepMergeJsonTree(oldTree, newPathTree);
                }

                //更新索引对应的数据
                String updateSQL = "UPDATE  "+ tableName +"  SET path_tree = ?, basic_path_num = ? WHERE id = ?;";
                try (PreparedStatement updateStatement = conn.prepareStatement(updateSQL)) {
                    updateStatement.setString(1, newPathTree.toJSONString());
                    updateStatement.setInt(2, newBasicPathNum);
                    updateStatement.setInt(3, selectedId);
                    int affectedRows = updateStatement.executeUpdate();
                    if (affectedRows > 0) {
                        generatedId = selectedId;
                    }
                }
            } else {
                // 记录不存在，插入新记录
                String insertSql = "INSERT INTO "+ tableName +" (req_proto, req_host_port, path_tree, basic_path_num) VALUES (?, ?, ?, ?);";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    insertStmt.setString(1, reqProto);
                    insertStmt.setString(2, reqHostPort);
                    insertStmt.setString(3, newPathTree.toJSONString());
                    insertStmt.setInt(4, newBasicPathNum);

                    int affectedRows = insertStmt.executeUpdate();

                    if (affectedRows > 0) {
                        // 获取生成的键值
                        try (ResultSet generatedKeys = insertStmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                generatedId = generatedKeys.getInt(1);
                            } else {
                                throw new SQLException("Creating user failed, no ID obtained.");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            stderr_println(String.format("[-] Error inserting or updating table [%s] -> Error:[%s]", tableName, e.getMessage()));
            e.printStackTrace();
        }

        return generatedId; // 返回ID值，无论是更新还是插入
    }


    //根据域名查询对应的Host
    public static synchronized PathTreeModel fetchPathTreeByReqHostPort(String reqHost) {
        PathTreeModel pathTreeModel= null;

        //查询
        String selectSql = "SELECT req_proto, req_host_port, path_tree, basic_path_num FROM "+ tableName +" WHERE req_host_port = ? LIMIT 1;";

        try (Connection conn = DBService.getInstance().getNewConn(); PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, reqHost);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                pathTreeModel = new PathTreeModel(
                        rs.getString("req_proto"),
                        rs.getString("req_host_port"),
                        rs.getInt("basic_path_num"),
                        rs.getString("path_tree")
                        );
            }
        } catch (Exception e) {
            stderr_println(String.format("[-] Error Fetch One table [%s] -> Error:[%s]", tableName, e.getMessage()));
            e.printStackTrace();
        }

        return pathTreeModel;
    }


    /**
     * 获取 所有 表中记录的 URL前置
     * @return
     */
    public static synchronized Set<String> fetchAllRecordPathUrlPrefix(){
        Set<String> set = new HashSet<>();
        String selectSQL = "SELECT DISTINCT req_proto || '://' || req_host_port AS  url_prefix FROM "+ tableName + ";";

        try (Connection conn = DBService.getInstance().getNewConn(); PreparedStatement stmt = conn.prepareStatement(selectSQL)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String urlPrefix = rs.getString("url_prefix");
                urlPrefix = urlPrefix.replace(":-1", "");
                set.add(urlPrefix);
            }
        } catch (Exception e) {
            stderr_println(String.format("[-] Error fetch All Record Path Url Prefix: %s", e.getMessage()));
            e.printStackTrace();
        }

        return set;
    }

}
