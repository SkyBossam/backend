package com.aicoo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.*;
import java.util.*;

@Component
public class Db {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public Db(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public static Map<String,Object> p(Object... pairs){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    private MapSqlParameterSource parameters(Map<String,?> values){var p=new MapSqlParameterSource();values.forEach((key,value)->p.addValue(key,value instanceof Instant i?Timestamp.from(i):value));return p;}
    public int update(String sql,Map<String,?> values){return jdbc.update(sql,parameters(values));}
    public List<Map<String,Object>> list(String sql,Map<String,?> values){return jdbc.query(sql,parameters(values),(rs,index)->{
        Map<String,Object> row=new LinkedHashMap<>();var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){Object value=rs.getObject(i);String type=meta.getColumnTypeName(i);
            if(value!=null&&("jsonb".equals(type)||"json".equals(type)))value=parse(rs.getString(i));
            else if(value instanceof Timestamp t)value=t.toInstant();else if(value instanceof java.sql.Date d)value=d.toLocalDate();
            row.put(meta.getColumnLabel(i),value);
        }return row;
    });}
    public Optional<Map<String,Object>> one(String sql,Map<String,?> values){return list(sql,values).stream().findFirst();}
    public Map<String,Object> required(String sql,Map<String,?> values,String code){return one(sql,values).orElseThrow(()->ApiException.notFound(code));}
    public String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON value",e);}}
    public Object parse(String text){try{return mapper.readValue(text,Object.class);}catch(Exception e){throw new IllegalStateException("Invalid stored JSON",e);}}
    @SuppressWarnings("unchecked") public static List<String> strings(Map<String,Object> row,String key){return (List<String>)row.getOrDefault(key,List.of());}
    public static UUID id(Map<String,Object> row,String key){return (UUID)row.get(key);}
    public static Instant instant(Map<String,Object> row,String key){return (Instant)row.get(key);}
    public static String text(Map<String,Object> row,String key){return (String)row.get(key);}
    public static Map<String,Object> view(Map<String,Object> row,String... keys){var result=new LinkedHashMap<String,Object>();for(String key:keys){StringBuilder camel=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_')upper=true;else{camel.append(upper?Character.toUpperCase(c):c);upper=false;}}result.put(camel.toString(),row.get(key));}return result;}
}
