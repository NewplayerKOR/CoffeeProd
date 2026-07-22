\set ON_ERROR_STOP on

SELECT JSONB_PRETTY(
    JSONB_AGG(
        JSONB_BUILD_OBJECT(
            'email', users.email,
            'password', 'password',
            'addressId', users.address_id
        )
        ORDER BY users.member_no
    )
)
FROM (
    SELECT SUBSTRING(member.email FROM 'user-([0-9]{6})@')::INTEGER AS member_no,
           member.email,
           address.address_id
    FROM member
    JOIN address
      ON address.member_id = member.member_id
     AND address.is_default = TRUE
    WHERE member.email ~ '^loadtest-user-[0-9]{6}@coffeeprod[.]local$'
      AND member.status = 'ACTIVE'
      AND SUBSTRING(member.email FROM 'user-([0-9]{6})@')::INTEGER <= :k6_user_count::INTEGER
) users;
