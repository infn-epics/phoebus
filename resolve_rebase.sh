#!/bin/bash
cd /Users/michelottilabs/progetti/phoebus

for i in $(seq 1 100); do
    conflicted=$(git diff --name-only --diff-filter=U 2>/dev/null)
    if [ -z "$conflicted" ]; then
        if [ -d .git/rebase-merge ]; then
            git add -A
            result=$(GIT_EDITOR=":" git rebase --continue 2>&1)
            echo "$result" | tail -3
            if echo "$result" | grep -q "Successfully rebased"; then
                echo "REBASE_COMPLETE"
                exit 0
            fi
            continue
        else
            echo "REBASE_COMPLETE"
            exit 0
        fi
    fi

    non_pom=$(echo "$conflicted" | grep -v 'pom\.xml$' | grep -v '\.classpath$' | grep -v '\.gitlab-ci\.yml$')
    if [ -n "$non_pom" ]; then
        echo "NEED_MANUAL_RESOLUTION:"
        echo "$non_pom"
        commit_msg=$(cat .git/rebase-merge/message 2>/dev/null | head -1)
        echo "COMMIT: $commit_msg"
        exit 1
    fi

    count=$(echo "$conflicted" | wc -l | tr -d ' ')
    echo "[$i] Resolving $count conflicts with master..."
    echo "$conflicted" | xargs git checkout --ours
    git add -A
    result=$(GIT_EDITOR=":" git rebase --continue 2>&1)

    if echo "$result" | grep -q "Successfully rebased"; then
        echo "REBASE_COMPLETE"
        exit 0
    fi
done

echo "LOOP_LIMIT_REACHED"
