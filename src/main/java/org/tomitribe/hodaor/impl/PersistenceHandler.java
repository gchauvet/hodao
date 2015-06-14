/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tomitribe.hodaor.impl;

import org.tomitribe.hodaor.Find;
import org.tomitribe.hodaor.MaxResults;
import org.tomitribe.hodaor.Merge;
import org.tomitribe.hodaor.NamedQuery;
import org.tomitribe.hodaor.Offset;
import org.tomitribe.hodaor.Optional;
import org.tomitribe.hodaor.Persist;
import org.tomitribe.hodaor.QueryParam;
import org.tomitribe.hodaor.Remove;
import org.tomitribe.hodaor.ValidationException;
import org.tomitribe.hodaor.util.Parameter;
import org.tomitribe.hodaor.util.Reflection;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Beautiful Source of DRY CRUD
 * <p/>
 * DRY: Don't Repeat Yourself CRUD: Create Read Update Delete
 *
 * @version $Revision$ $Date$
 */
public class PersistenceHandler {

    private PersistenceHandler() {
    }

    public static Object invoke(final EntityManager em, final Method method, final Object[] args) throws Throwable {

        if (method.isAnnotationPresent(NamedQuery.class)) {

            return invokeNamedQuery(em, method, args);

        }

        if (method.isAnnotationPresent(Find.class)) {

            return findByPrimaryKey(em, method, args);

        }

        if (method.isAnnotationPresent(Merge.class)) {

            return merge(em, method, args);

        }

        if (method.isAnnotationPresent(Remove.class)) {

            return remove(em, method, args);

        }

        if (method.isAnnotationPresent(Persist.class)) {

            return persist(em, method, args);

        }

        throw new AbstractMethodError("No handler logic for method: " + method.toString());
    }

    /**
     * CREATE
     * <p/>
     * Persist the specified entity
     *
     * @param em
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    public static Object persist(final EntityManager em, final Method method, final Object[] args) throws Throwable {
        final Iterable<Parameter> params = Reflection.params(method, args);
        final Parameter parameter = params.iterator().next();

        if (parameter.getValue() == null) {
            throw new ValidationException(parameter.getType().getSimpleName() + " object is null");
        }

        em.persist(parameter.getValue());

        return parameter.getValue();
    }

    /**
     * READ:
     * <p/>
     * Find an entity by primary key
     *
     * @param em
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    public static Object findByPrimaryKey(final EntityManager em, final Method method, final Object[] args) throws Throwable {
        final Class<?> entityClass = method.getReturnType();
        final Object primaryKey = args[0];

        if (primaryKey == null) {
            throw new ValidationException("Invalid id");
        }
        return em.find(entityClass, primaryKey);
    }

    /**
     * READ:
     * <p/>
     * Execute a NamedQuery
     *
     * @param em
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    public static Object invokeNamedQuery(final EntityManager em, final Method method, final Object[] args) throws Throwable {
        final NamedQuery namedQuery = method.getAnnotation(NamedQuery.class);

        if (namedQuery.update()) {

            return update(em, method, args, namedQuery);

        } else {

            return select(em, method, args, namedQuery);
        }
    }

    private static Object update(final EntityManager em, final Method method, final Object[] args, final NamedQuery namedQuery) {

        final Query query = em.createNamedQuery(namedQuery.value());

        for (final Parameter parameter : Reflection.params(method, args)) {
            final QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
            if (queryParam != null) {
                if (parameter.getValue() == null) {
                    throw new ValidationException(queryParam.value() + " is null");
                }

                query.setParameter(queryParam.value(), parameter.getValue());
            }
        }

        if (isInt(method.getReturnType())) {

            return query.executeUpdate();

        } else if (isVoid(method.getReturnType())) {

            query.executeUpdate();

            return null;

        } else {

            throw new IllegalArgumentException("Update methods must have a void or int return type");
        }
    }

    private static Object select(final EntityManager em, final Method method, final Object[] args, final NamedQuery namedQuery) {

        final boolean optional = method.getAnnotation(Optional.class) != null;

        final Query query = em.createNamedQuery(namedQuery.value());

        Integer offset = null;
        Integer maxResults = null;

        for (final Parameter parameter : Reflection.params(method, args)) {
            final QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
            if (queryParam != null) {
                if (parameter.getValue() == null) {
                    throw new ValidationException(queryParam.value() + " is null");
                }

                query.setParameter(queryParam.value(), parameter.getValue());
            }

            final Offset o = parameter.getAnnotation(Offset.class);
            if (o != null && (isInt(parameter.getType()))) {
                offset = (Integer) parameter.getValue();
            }

            final MaxResults m = parameter.getAnnotation(MaxResults.class);
            if (m != null && (isInt(parameter.getType()))) {
                maxResults = (Integer) parameter.getValue();
            }
        }

        if (offset != null && maxResults != null) {
            query.setFirstResult(offset);
            query.setMaxResults(maxResults);
        }

        try {

            return (isList(method)) ? query.getResultList() : query.getSingleResult();

        } catch (final NoResultException e) {

            // if we don't require that this actually returns a value, we can return null
            if (optional) {
                return null;
            }

            throw e;
        }
    }

    /**
     * UPDATE
     * <p/>
     * Perform a merge on the passed in entity
     *
     * @param em
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    public static Object merge(final EntityManager em, final Method method, final Object[] args) throws Throwable {
        final Iterable<Parameter> params = Reflection.params(method, args);
        final Parameter parameter = params.iterator().next();

        if (parameter.getValue() == null) {
            throw new ValidationException(parameter.getType().getSimpleName() + " object is null");
        }

        return em.merge(parameter.getValue());
    }

    /**
     * DELETE
     * <p/>
     * Remove the specified entity
     *
     * @param em
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    public static Object remove(final EntityManager em, final Method method, final Object[] args) throws Throwable {
        final Iterable<Parameter> params = Reflection.params(method, args);
        final Parameter parameter = params.iterator().next();

        if (parameter.getValue() == null) {
            throw new ValidationException(parameter.getType().getSimpleName() + " object is null");
        }

        em.remove(em.merge(parameter.getValue()));

        return null;
    }

    /**
     * Is the return value a list?
     *
     * @param method
     * @return
     */
    private static boolean isList(final Method method) {
        return Collection.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Is the specified type an int?
     *
     * @param clazz
     * @return
     */
    private static boolean isInt(final Class<?> clazz) {
        return Integer.class.isAssignableFrom(clazz) || Integer.TYPE.isAssignableFrom(clazz);
    }

    /**
     * Is the specified type void?
     *
     * @param clazz
     * @return
     */
    private static boolean isVoid(final Class<?> clazz) {
        return Void.class.equals(clazz) || Void.TYPE.equals(clazz);
    }

}
