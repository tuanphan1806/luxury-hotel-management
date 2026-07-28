package com.hotel.backend.persistence;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Persists PostgreSQL {@code time without time zone} through JDBC 4.2.
 *
 * <p>The application intentionally keeps Hibernate's global JDBC timezone at
 * UTC for existing financial timestamps. Hibernate's legacy {@code TIME}
 * adapter otherwise converts {@link LocalTime} through {@code java.sql.Time}
 * and the JVM timezone; in Asia/Saigon that even picks the historical 1970
 * offset and changes a policy value such as 12:00 into 20:00. Direct JDBC 4.2
 * binding keeps these wall-clock policy values timezone-free without changing
 * the storage semantics of existing reservation timestamps.</p>
 */
public final class LocalTimeWithoutTimezoneType implements UserType<LocalTime> {

    @Override
    public int getSqlType() {
        return Types.TIME;
    }

    @Override
    public Class<LocalTime> returnedClass() {
        return LocalTime.class;
    }

    @Override
    public boolean equals(LocalTime left, LocalTime right) {
        return Objects.equals(left, right);
    }

    @Override
    public int hashCode(LocalTime value) {
        return Objects.hashCode(value);
    }

    @Override
    public LocalTime nullSafeGet(
            ResultSet resultSet,
            int position,
            SharedSessionContractImplementor session,
            Object owner) throws SQLException {
        return resultSet.getObject(position, LocalTime.class);
    }

    @Override
    public void nullSafeSet(
            PreparedStatement statement,
            LocalTime value,
            int index,
            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIME);
            return;
        }
        statement.setObject(index, value, Types.TIME);
    }

    @Override
    public LocalTime deepCopy(LocalTime value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(LocalTime value) {
        return value;
    }

    @Override
    public LocalTime assemble(Serializable cached, Object owner) {
        return (LocalTime) cached;
    }
}
